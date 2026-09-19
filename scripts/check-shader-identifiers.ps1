# Dev-only guard against naming a GLSL declaration after a reserved word or builtin.
# A `float flat = ...` / `vec2 step = ...` silently shadows (or is rejected by) the GLSL
# compiler, which breaks the whole pipeline and drops every resource pack (black screen).
# See AGENTS.md ("Never name a uniform ... after a GLSL built-in"). Not shipped in the jar.
#
# Usage: powershell -File scripts/check-shader-identifiers.ps1
# Exits 1 (after listing file:line) when a forbidden identifier is declared; 0 when clean.
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$shaderRoot = Join-Path $repoRoot "src\client\resources\assets\vfxweaver\shaders"

# Reserved words / interpolation qualifiers that may never be an identifier, plus builtin
# functions and variables that shadow the builtin when used as one. Matched only after a type
# specifier, so legitimate uses of `mix(...)`/`texture(...)` are not reported.
$forbidden = @(
	"flat", "smooth", "noperspective", "centroid", "sample", "patch", "subroutine",
	"layout", "buffer", "shared", "coherent", "volatile", "restrict", "readonly",
	"writeonly", "atomic_uint", "in", "out", "inout", "const", "do", "while", "switch",
	"case", "default", "struct", "union", "enum", "typedef", "this", "namespace", "using",
	"public", "private", "protected", "template", "class", "discard", "return",
	"length", "mix", "step", "mod", "clamp", "texture", "textureLod", "texelFetch",
	"textureSize", "normalize", "dot", "cross", "distance", "reflect", "refract",
	"smoothstep", "round", "sign", "abs", "floor", "fract", "min", "max", "pow", "sqrt",
	"exp", "log", "sin", "cos", "tan", "atan", "radians", "degrees", "isnan", "isinf",
	"all", "any", "not", "equal", "notEqual", "dFdx", "dFdy", "fwidth"
) | Sort-Object -Unique

$types = "float|double|int|uint|bool|void|vec2|vec3|vec4|ivec2|ivec3|ivec4|bvec2|bvec3|bvec4|mat2|mat3|mat4|uvec2|uvec3|uvec4|sampler2D|sampler3D|samplerCube"

# `<type> <name>` starting a declaration or a parameter; the name is the capture group.
$pattern = "(?:^|[^\w.])(?:$types)\s+($($forbidden -join '|'))\b"

$problems = New-Object System.Collections.Generic.List[string]
$files = Get-ChildItem -Path $shaderRoot -Recurse -File -Include *.fsh, *.vsh, *.glsl, *.vert, *.frag
foreach ($file in $files) {
	$text = Get-Content -LiteralPath $file.FullName -Raw
	# Strip line comments and block comments before scanning so prose does not trip the check.
	$code = [regex]::Replace($text, "/\*.*?\*/", "", "Singleline")
	$code = [regex]::Replace($code, "//[^\r\n]*", "")
	$line = 0
	foreach ($rawLine in ($code -split "\r?\n")) {
		$line++
		foreach ($m in [regex]::Matches($rawLine, $pattern)) {
			$name = $m.Groups[1].Value
			$rel = Resolve-Path -LiteralPath $file.FullName -Relative
			$problems.Add("${rel}:${line}: declaration uses forbidden GLSL identifier '$name'")
		}
	}
}

if ($problems.Count -gt 0) {
	$problems | ForEach-Object { Write-Host $_ }
	Write-Error "Found $($problems.Count) forbidden GLSL identifier(s)."
	exit 1
}
Write-Host "No forbidden GLSL identifiers in $($files.Count) shader source(s)."
exit 0
