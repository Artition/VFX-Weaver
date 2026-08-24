package dev.vfxweaver.effect;

import dev.vfxweaver.noise.SimplexNoise;
import java.util.ArrayList;
import java.util.List;

/**
 * A compiled mathematical expression (Recursive Descent Parser) evaluated with
 * {@link #eval(float, float, float, float, float)}. Parsed once when the datapack is loaded
 * (in {@code VFXDefinition}), then evaluated every frame by walking the AST — the source string
 * is never re-parsed per frame.
 *
 * <p>Available variables: {@code t} (ticks since effect start), {@code x}/{@code y}/{@code z}
 * (world/camera coordinates), {@code pi}, {@code e}.
 *
 * <p>Available functions: {@code sin}, {@code cos}, {@code abs}, {@code min}, {@code max},
 * {@code pow}, {@code sqrt}, {@code random()} (0..1, deterministic per instance seed),
 * {@code noise(x,y,z)} (3D simplex noise in -1..1).
 */
public final class MathExpression {
	/** A random-ish 0..1 value derived from a seed and a call counter (deterministic per instance). */
	private final long seed;
	private int randomCalls;

	private final Node root;

	private MathExpression(final long seed, final Node root) {
		this.seed = seed;
		this.root = root;
	}

	/**
	 * Compiles the given expression string. Returns {@code null} when the string is invalid
	 * (caller logs and falls back to {@code 0}).
	 *
	 * @param seed per-instance seed used to vary {@code random()} between instances
	 * @param expr the expression source
	 */
	/** Hard cap on expression source length - the recursive descent parser has no depth limit of
	 * its own, and a pathological nest of parentheses would otherwise overflow the stack. */
	private static final int MAX_SOURCE_LENGTH = 4096;

	public static @org.jspecify.annotations.Nullable MathExpression compile(final long seed, final String expr) {
		if (expr == null || expr.isBlank() || expr.length() > MAX_SOURCE_LENGTH) {
			return null;
		}
		try {
			Parser parser = new Parser(expr);
			Node node = parser.parseExpression();
			if (!parser.atEnd()) {
				throw new IllegalArgumentException("Unexpected trailing input at position " + parser.pos());
			}
			return new MathExpression(seed, node);
		} catch (StackOverflowError e) {
			// A deeply nested expression blew the parser stack - treat as invalid input, not a crash.
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Evaluates the expression.
	 *
	 * @param t time in ticks since the effect started
	 * @param x world/camera X
	 * @param y world/camera Y
	 * @param z world/camera Z
	 */
	public float eval(final float t, final float x, final float y, final float z) {
		return this.root.eval(new Ctx(t, x, y, z));
	}

	private final class Ctx {
		final float t;
		final float x;
		final float y;
		final float z;

		Ctx(final float t, final float x, final float y, final float z) {
			this.t = t;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		float random() {
			// Deterministic pseudo-random in [0,1) from the instance seed and a call counter.
			long h = seed ^ (randomCalls++ * 0x9E3779B97F4A7C15L);
			h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
			h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
			h = h ^ (h >>> 31);
			return ((h & 0xFFFFFFFFL) / (float) 0x100000000L);
		}
	}

	// ---------------------------------------------------------------------------------------
	// AST
	// ---------------------------------------------------------------------------------------

	private interface Node {
		float eval(Ctx ctx);
	}

	private static final class NumberNode implements Node {
		final float value;

		NumberNode(final float value) {
			this.value = value;
		}

		@Override
		public float eval(final Ctx ctx) {
			return this.value;
		}
	}

	private static final class VarNode implements Node {
		final char var;

		VarNode(final char var) {
			this.var = var;
		}

		@Override
		public float eval(final Ctx ctx) {
			return switch (this.var) {
				case 't' -> ctx.t;
				case 'x' -> ctx.x;
				case 'y' -> ctx.y;
				case 'z' -> ctx.z;
				default -> Float.NaN;
			};
		}
	}

	private static final class ConstNode implements Node {
		final float value;

		ConstNode(final float value) {
			this.value = value;
		}

		@Override
		public float eval(final Ctx ctx) {
			return this.value;
		}
	}

	/**
	 * A variable resolved from the current local player state ({@code health}, {@code hunger},
	 * {@code speed}, {@code light_level}, {@code time_of_day}, {@code player_x/y/z}). Returns
	 * {@code NaN} when no player state is available (e.g. dedicated server).
	 */
	private static final class PlayerVarNode implements Node {
		final String name;

		PlayerVarNode(final String name) {
			this.name = name;
		}

		@Override
		public float eval(final Ctx ctx) {
			VFXWorldBindings.PlayerState state = VFXWorldBindings.playerState();
			if (state == null) {
				return Float.NaN;
			}
			return switch (this.name) {
				case "health" -> state.health();
				case "hunger" -> state.hunger();
				case "speed" -> state.speed();
				case "light_level" -> state.light();
				case "time_of_day" -> state.timeOfDay();
				case "player_x" -> state.px();
				case "player_y" -> state.py();
				case "player_z" -> state.pz();
				default -> Float.NaN;
			};
		}
	}

	private static final class BinaryNode implements Node {
		final char op;
		final Node left;
		final Node right;

		BinaryNode(final char op, final Node left, final Node right) {
			this.op = op;
			this.left = left;
			this.right = right;
		}

		@Override
		public float eval(final Ctx ctx) {
			float a = this.left.eval(ctx);
			float b = this.right.eval(ctx);
			return switch (this.op) {
				case '+' -> a + b;
				case '-' -> a - b;
				case '*' -> a * b;
				case '/' -> a / b;
				default -> Float.NaN;
			};
		}
	}

	private static final class NegNode implements Node {
		final Node inner;

		NegNode(final Node inner) {
			this.inner = inner;
		}

		@Override
		public float eval(final Ctx ctx) {
			return -this.inner.eval(ctx);
		}
	}

	private static final class FnNode implements Node {
		final int fn;
		final List<Node> args;

		FnNode(final int fn, final List<Node> args) {
			this.fn = fn;
			this.args = args;
		}

		@Override
		public float eval(final Ctx ctx) {
			return switch (this.fn) {
				case 0 -> (float) Math.sin(arg(ctx, 0));
				case 1 -> (float) Math.cos(arg(ctx, 0));
				case 2 -> Math.abs(arg(ctx, 0));
				case 3 -> Math.min(arg(ctx, 0), arg(ctx, 1));
				case 4 -> Math.max(arg(ctx, 0), arg(ctx, 1));
				case 5 -> (float) Math.pow(arg(ctx, 0), arg(ctx, 1));
				case 6 -> (float) Math.sqrt(arg(ctx, 0));
				case 7 -> ctx.random();
				case 8 -> (float) SimplexNoise.noise(arg(ctx, 0), arg(ctx, 1), arg(ctx, 2));
				default -> Float.NaN;
			};
		}

		private float arg(final Ctx ctx, final int i) {
			return this.args.get(i).eval(ctx);
		}
	}

	// Function ids
	private static final int FN_SIN = 0;
	private static final int FN_COS = 1;
	private static final int FN_ABS = 2;
	private static final int FN_MIN = 3;
	private static final int FN_MAX = 4;
	private static final int FN_POW = 5;
	private static final int FN_SQRT = 6;
	private static final int FN_RANDOM = 7;
	private static final int FN_NOISE = 8;

	// ---------------------------------------------------------------------------------------
	// Recursive Descent Parser
	// ---------------------------------------------------------------------------------------

	private static final class Parser {
		private final String src;
		private int i;

		Parser(final String src) {
			this.src = src;
		}

		int pos() {
			return this.i;
		}

		boolean atEnd() {
			skipWs();
			return this.i >= this.src.length();
		}

		Node parseExpression() {
			Node node = parseTerm();
			while (true) {
				skipWs();
				if (this.i >= this.src.length()) {
					return node;
				}
				char c = this.src.charAt(this.i);
				if (c == '+' || c == '-') {
					this.i++;
					node = new BinaryNode(c, node, parseTerm());
				} else {
					return node;
				}
			}
		}

		private Node parseTerm() {
			Node node = parseFactor();
			while (true) {
				skipWs();
				if (this.i >= this.src.length()) {
					return node;
				}
				char c = this.src.charAt(this.i);
				if (c == '*' || c == '/') {
					this.i++;
					node = new BinaryNode(c, node, parseFactor());
				} else {
					return node;
				}
			}
		}

		private Node parseFactor() {
			skipWs();
			if (this.i >= this.src.length()) {
				throw new IllegalArgumentException("Unexpected end of expression");
			}
			char c = this.src.charAt(this.i);
			if (c == '-') {
				this.i++;
				return new NegNode(parseFactor());
			}
			if (c == '+') {
				this.i++;
				return parseFactor();
			}
			if (c == '(') {
				this.i++;
				Node node = parseExpression();
				skipWs();
				expect(')');
				return node;
			}
			if (Character.isDigit(c) || c == '.') {
				return parseNumber();
			}
			// identifier: variable, constant or function
			return parseIdentifier();
		}

		private Node parseNumber() {
			int start = this.i;
			boolean dot = false;
			while (this.i < this.src.length()) {
				char c = this.src.charAt(this.i);
				if (Character.isDigit(c)) {
					this.i++;
				} else if (c == '.' && !dot) {
					dot = true;
					this.i++;
				} else {
					break;
				}
			}
			float value = Float.parseFloat(this.src.substring(start, this.i));
			return new NumberNode(value);
		}

		private Node parseIdentifier() {
			int start = this.i;
			while (this.i < this.src.length() && (Character.isLetter(this.src.charAt(this.i)) || this.src.charAt(this.i) == '_')) {
				this.i++;
			}
			String name = this.src.substring(start, this.i);
			skipWs();
			boolean isCall = this.i < this.src.length() && this.src.charAt(this.i) == '(';
			if (isCall) {
				return parseFunction(name);
			}
			return switch (name) {
				case "pi" -> new ConstNode((float) Math.PI);
				case "e" -> new ConstNode((float) Math.E);
				case "t" -> new VarNode('t');
				case "x" -> new VarNode('x');
				case "y" -> new VarNode('y');
				case "z" -> new VarNode('z');
				case "health", "hunger", "speed", "light_level", "time_of_day", "player_x", "player_y", "player_z" -> new PlayerVarNode(name);
				default -> throw new IllegalArgumentException("Unknown variable or constant: " + name);
			};
		}

		private Node parseFunction(final String name) {
			int fn = switch (name) {
				case "sin" -> FN_SIN;
				case "cos" -> FN_COS;
				case "abs" -> FN_ABS;
				case "min" -> FN_MIN;
				case "max" -> FN_MAX;
				case "pow" -> FN_POW;
				case "sqrt" -> FN_SQRT;
				case "random" -> FN_RANDOM;
				case "noise" -> FN_NOISE;
				default -> throw new IllegalArgumentException("Unknown function: " + name);
			};
			this.i++; // consume '('
			List<Node> args = new ArrayList<>();
			skipWs();
			if (this.i < this.src.length() && this.src.charAt(this.i) == ')') {
				this.i++;
				return new FnNode(fn, args);
			}
			while (true) {
				args.add(parseExpression());
				skipWs();
				if (this.i >= this.src.length()) {
					throw new IllegalArgumentException("Unterminated argument list");
				}
				char c = this.src.charAt(this.i);
				if (c == ',') {
					this.i++;
					continue;
				}
				if (c == ')') {
					this.i++;
					break;
				}
				throw new IllegalArgumentException("Expected ',' or ')' in argument list");
			}
			return new FnNode(fn, args);
		}

		private void skipWs() {
			while (this.i < this.src.length() && Character.isWhitespace(this.src.charAt(this.i))) {
				this.i++;
			}
		}

		private void expect(final char c) {
			if (this.i >= this.src.length() || this.src.charAt(this.i) != c) {
				throw new IllegalArgumentException("Expected '" + c + "'");
			}
			this.i++;
		}
	}
}
