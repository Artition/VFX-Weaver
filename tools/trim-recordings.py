#!/usr/bin/env python3
"""Trim Minecraft effect-showcase recordings into clean, ordered clips.

Reads every video found in INPUT, sorts the files by modification time
(oldest first = recording order), cuts --lead seconds off the front of each,
and writes at most --duration seconds to OUTPUT as 001_<stem>.mp4, ...

Per-file loop lengths come from --durations FILE, one "key=seconds" per line,
where key is the 1-based order index, the file name or the file stem.
Blank lines and lines starting with '#' are ignored.

Usage:
	python tools/trim-recordings.py INPUT OUTPUT [options]
	python tools/trim-recordings.py in/ out/ --lead 2 --duration 8 --dry-run

Requires ffmpeg and ffprobe on PATH.
"""

import argparse
import json
import shlex
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

VIDEO_EXTS = {".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v", ".flv", ".wmv", ".ts", ".mpg", ".mpeg"}
MIN_CLIP = 0.1
FFMPEG = "ffmpeg"
FFPROBE = "ffprobe"


def log(message):
	print(message, flush=True)


def fail(message):
	print("error: " + message, file=sys.stderr, flush=True)
	return 2


def secs(value):
	return f"{value:.3f}"


def require_tools():
	missing = [tool for tool in (FFMPEG, FFPROBE) if shutil.which(tool) is None]
	if not missing:
		return None
	return fail(
		"{0} not found on PATH. Install a full ffmpeg build and reopen the terminal:\n"
		"  Windows: winget install Gyan.FFmpeg   (or choco install ffmpeg)\n"
		"  macOS:   brew install ffmpeg\n"
		"  Linux:   sudo apt install ffmpeg".format(", ".join(missing))
	)


def probe_media(path):
	proc = subprocess.run(
		[FFPROBE, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(path)],
		capture_output=True, text=True, encoding="utf-8", errors="replace",
	)
	if proc.returncode != 0:
		raise RuntimeError(proc.stderr.strip() or "ffprobe failed")
	info = json.loads(proc.stdout or "{}")
	video = None
	has_audio = False
	for stream in info.get("streams", []):
		if stream.get("codec_type") == "video" and video is None:
			video = stream
		elif stream.get("codec_type") == "audio":
			has_audio = True
	if video is None:
		raise RuntimeError("no video stream")
	duration = None
	for candidate in (info.get("format", {}).get("duration"), video.get("duration")):
		if candidate not in (None, "N/A"):
			try:
				duration = float(candidate)
				break
			except ValueError:
				pass
	if duration is None:
		raise RuntimeError("could not determine duration")
	fps = video.get("r_frame_rate") or video.get("avg_frame_rate") or "30/1"
	if fps in ("", "0/0"):
		fps = "30/1"
	return {"duration": duration, "fps": fps, "has_audio": has_audio}


def collect(input_dir):
	files = [p for p in input_dir.iterdir() if p.is_file() and p.suffix.lower() in VIDEO_EXTS]
	return sorted(files, key=lambda p: (p.stat().st_mtime, p.name))


def load_overrides(path):
	overrides = {}
	text = Path(path)
	if not text.is_file():
		raise FileNotFoundError(f"durations file does not exist: {path}")
	for number, raw in enumerate(text.read_text(encoding="utf-8-sig").splitlines(), 1):
		line = raw.strip()
		if not line or line.startswith("#"):
			continue
		if "=" not in line:
			raise ValueError(f"{path}:{number}: expected 'key=seconds', got {line!r}")
		key, value = line.split("=", 1)
		try:
			overrides[key.strip().lower()] = float(value.strip())
		except ValueError:
			raise ValueError(f"{path}:{number}: {value!r} is not a number")
	return overrides


def resolve_override(overrides, index, path):
	for key in (str(index), path.name.lower(), path.stem.lower()):
		if key in overrides:
			return overrides[key]
	return None


def build_standard_cmd(src, out, start, length, fps, crf, keep_audio, has_audio, overwrite):
	cmd = [
		FFMPEG, "-hide_banner", "-loglevel", "error", "-nostdin",
		"-ss", secs(start), "-i", str(src), "-t", secs(length),
		"-vf", f"fps={fps},format=yuv420p",
		"-c:v", "libx264", "-preset", "medium", "-crf", str(crf),
		"-pix_fmt", "yuv420p", "-fps_mode", "cfr",
	]
	if keep_audio and has_audio:
		cmd += ["-map", "0:v:0", "-map", "0:a:0?", "-c:a", "aac", "-b:a", "128k"]
	else:
		cmd += ["-an"]
	cmd += ["-movflags", "+faststart", "-y" if overwrite else "-n", str(out)]
	return cmd


def build_loop_cmd(src, out, start, length, fade, fps, crf, overwrite):
	body_end = length - fade
	filter_complex = (
		f"[0:v]fps={fps},format=yuv420p,split=3[s0][s1][s2];"
		f"[s0]trim=start=0:end={secs(fade)},setpts=PTS-STARTPTS[head];"
		f"[s1]trim=start={secs(body_end)}:end={secs(length)},setpts=PTS-STARTPTS[tail];"
		f"[s2]trim=start={secs(fade)}:end={secs(body_end)},setpts=PTS-STARTPTS[body];"
		f"[tail][head]xfade=transition=fade:duration={secs(fade)}:offset=0[junction];"
		f"[body][junction]concat=n=2:v=1:a=0[outv]"
	)
	cmd = [
		FFMPEG, "-hide_banner", "-loglevel", "error", "-nostdin",
		"-ss", secs(start), "-i", str(src),
		"-filter_complex", filter_complex, "-map", "[outv]",
		"-c:v", "libx264", "-preset", "medium", "-crf", str(crf),
		"-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart",
		"-y" if overwrite else "-n", str(out),
	]
	return cmd


def report_order(medias):
	log("Recording order (oldest modification time first):")
	for index, media in enumerate(medias, 1):
		stamp = datetime.fromtimestamp(media["path"].stat().st_mtime).strftime("%Y-%m-%d %H:%M:%S")
		if media["error"]:
			log(f"  {index:>3}  {stamp}  {media['path'].name}  (unreadable: {media['error']})")
		else:
			log(f"  {index:>3}  {stamp}  {media['path'].name}  [{secs(media['duration'])}s, {media['fps']}]")


def plan_media(media, index, args, overrides, out_dir, width):
	path = media["path"]
	remaining = media["duration"] - args.lead
	if remaining <= MIN_CLIP:
		return None, f"shorter than --lead ({args.lead}s): src {secs(media['duration'])}s"
	target = resolve_override(overrides, index, path)
	if target is None:
		target = args.duration
	length = remaining if target <= 0 else min(target, remaining)
	if length <= MIN_CLIP:
		return None, f"nothing left after --lead ({secs(remaining)}s remaining)"
	fade = args.loop_fade
	note = None
	if fade > 0 and length <= 2 * fade + MIN_CLIP:
		note = f"cannot loop-fade {secs(fade)}s into a {secs(length)}s clip; writing a plain clip"
		fade = 0.0
	out_path = out_dir / f"{index:0{width}d}_{path.stem}.mp4"
	plan = {
		"index": index, "media": media, "src": media["duration"],
		"lead": args.lead, "length": length, "fade": fade,
		"out": out_path, "note": note,
	}
	return plan, None


def main(argv=None):
	for stream in (sys.stdout, sys.stderr):
		try:
			stream.reconfigure(encoding="utf-8", errors="replace")
		except (AttributeError, ValueError):
			pass

	parser = argparse.ArgumentParser(
		description="Trim Minecraft effect-showcase recordings into clean, ordered clips.",
		formatter_class=argparse.RawDescriptionHelpFormatter,
		epilog=(
			"durations file format:\n"
			"  # key is the 1-based order index, the file name or the file stem\n"
			"  1=8.0\n"
			"  012_big_explosion.mp4=10.5\n"
			"  ripple=9"
		),
	)
	parser.add_argument("input", metavar="INPUT", help="directory containing the recordings")
	parser.add_argument("output", metavar="OUTPUT", help="directory to write the clips into")
	parser.add_argument("--lead", type=float, default=2.0, metavar="SEC",
		help="seconds cut off the front of each recording (default: 2.0)")
	parser.add_argument("--duration", type=float, default=8.0, metavar="SEC",
		help="target clip length in seconds; 0 keeps all remaining footage (default: 8.0)")
	parser.add_argument("--durations", metavar="FILE",
		help="per-file overrides, one 'key=seconds' per line (see below)")
	parser.add_argument("--loop-fade", nargs="?", const=0.5, default=0.0, type=float, metavar="SEC",
		help="crossfade the last SEC seconds into the first for a seamless loop; "
			"SEC defaults to 0.5, 0 disables (default: 0)")
	parser.add_argument("--fps", type=float, default=0.0, metavar="FPS",
		help="force output frame rate; 0 keeps the source rate (default: 0)")
	parser.add_argument("--crf", type=int, default=20, metavar="N",
		help="H.264 quality, lower is better (default: 20)")
	parser.add_argument("--keep-audio", action="store_true",
		help="keep audio in plain clips (dropped by default; loop-fade is always silent)")
	parser.add_argument("--overwrite", action="store_true",
		help="allow overwriting existing output files (default: refuse and skip)")
	parser.add_argument("--dry-run", action="store_true",
		help="print the plan without writing anything")
	args = parser.parse_args(argv)

	if args.lead < 0:
		return fail("--lead must not be negative")
	if args.duration < 0:
		return fail("--duration must not be negative")
	if args.loop_fade < 0:
		return fail("--loop-fade must not be negative")
	if args.fps < 0:
		return fail("--fps must not be negative")
	if not 0 <= args.crf <= 51:
		return fail("--crf must be between 0 and 51")

	problem = require_tools()
	if problem:
		return problem

	input_dir = Path(args.input)
	if not input_dir.exists():
		return fail(f"input directory does not exist: {input_dir}")
	if not input_dir.is_dir():
		return fail(f"input is not a directory: {input_dir}")

	files = collect(input_dir)
	if not files:
		return fail(f"no video files found in {input_dir} (looked for {', '.join(sorted(VIDEO_EXTS))})")

	medias = []
	for path in files:
		try:
			probe = probe_media(path)
			medias.append({"path": path, "error": None, **probe})
		except (RuntimeError, json.JSONDecodeError) as exc:
			medias.append({"path": path, "error": str(exc), "duration": 0.0, "fps": "?", "has_audio": False})

	report_order(medias)

	overrides = {}
	if args.durations:
		try:
			overrides = load_overrides(args.durations)
		except (FileNotFoundError, ValueError) as exc:
			return fail(str(exc))

	out_dir = Path(args.output)
	width = max(3, len(str(len(medias))))
	plans = []
	for index, media in enumerate(medias, 1):
		if media["error"]:
			plans.append(("skip", index, media, f"unreadable: {media['error']}", None))
			continue
		plan, reason = plan_media(media, index, args, overrides, out_dir, width)
		if plan is None:
			plans.append(("skip", index, media, reason, None))
			continue
		if args.fps:
			plan["media"] = dict(plan["media"])
			plan["media"]["fps"] = f"{args.fps:g}"
		if plan["out"].exists() and not args.overwrite:
			plans.append(("skip", index, media, f"output exists: {plan['out']} (use --overwrite)", None))
			continue
		plans.append(("plan", index, media, None, plan))

	if args.dry_run:
		log("")
		log("Dry run - nothing will be written:")
		for kind, index, media, reason, plan in plans:
			if kind == "skip":
				log(f"  {index:>3}  skip: {reason}")
				continue
			mode = "plain"
			if plan["fade"] > 0:
				mode = f"loop-fade {secs(plan['fade'])}s -> {secs(plan['length'] - plan['fade'])}s"
			if plan["note"]:
				log(f"  {index:>3}  note: {plan['note']}")
			log(f"  {index:>3}  cut lead {secs(plan['lead'])}s -> {secs(plan['length'])}s  "
				f"{mode}  {plan['out']}")
			cmd = ffmpeg_cmd(plan, args, media)
			log("       " + shlex.join(str(part) for part in cmd))
		return 0

	out_dir.mkdir(parents=True, exist_ok=True)
	log("")
	log(f"Writing to {out_dir}")

	results = []
	written = 0
	for kind, index, media, reason, plan in plans:
		if kind == "skip":
			results.append((index, media["path"].name, None, reason))
			log(f"  {index:>3}  skip: {reason}")
			continue
		if plan["note"]:
			log(f"  {index:>3}  warning: {plan['note']}")
		cmd = ffmpeg_cmd(plan, args, media)
		proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
		if proc.returncode != 0:
			message = proc.stderr.strip().splitlines()
			message = message[-1] if message else "ffmpeg failed"
			results.append((index, media["path"].name, None, message))
			log(f"  {index:>3}  failed: {message}")
			continue
		out_duration = plan["length"]
		try:
			out_duration = probe_media(plan["out"])["duration"]
		except (RuntimeError, json.JSONDecodeError):
			pass
		written += 1
		results.append((index, media["path"].name, plan, None))
		log(f"  {index:>3}  wrote {plan['out']}  ({secs(out_duration)}s)")

	log("")
	log(f"Summary: {written} clip(s) written, {len(plans) - written} skipped/failed")
	for index, name, plan, reason in results:
		if plan is None:
			log(f"  {index:>3}  {name}: skipped ({reason})")
		else:
			log(f"  {index:>3}  {name}: src {secs(plan['src'])}s, cut lead {secs(plan['lead'])}s, "
				f"clip {secs(plan['length'])}s -> {plan['out']}")
	return 0


def ffmpeg_cmd(plan, args, media):
	media = plan["media"]
	if plan["fade"] > 0:
		return build_loop_cmd(
			media["path"], plan["out"], plan["lead"], plan["length"],
			plan["fade"], media["fps"], args.crf, args.overwrite,
		)
	return build_standard_cmd(
		media["path"], plan["out"], plan["lead"], plan["length"],
		media["fps"], args.crf, args.keep_audio, media["has_audio"], args.overwrite,
	)


if __name__ == "__main__":
	sys.exit(main())
