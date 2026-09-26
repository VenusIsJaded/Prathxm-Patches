package app.prathxm.chess.extension.stockfish;

import android.content.Context;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * StockfishBridge – singleton façade over StockfishProcess.
 *
 * Keeps a small LRU cache of finished searches keyed by position, so the same position
 * is never searched twice at the same (or lower) depth – e.g. when the board callback fires
 * several times for one move, when stepping back and forth through a game, or when browsing
 * a game that was just reviewed. This removes a lot of redundant CPU work (and heat).
 */
@SuppressWarnings("unused")
public class StockfishBridge {

    private static final String TAG = "StockfishBridge";

    private static final StockfishProcess engine = new StockfishProcess();
    private static volatile boolean initialised = false;

    private static final int CACHE_SIZE = 512;

    private static final class CacheEntry {
        final StockfishProcess.AnalysisResult result;
        final int depth;
        final int multiPV;
        final boolean limited;

        CacheEntry(StockfishProcess.AnalysisResult result, int depth, int multiPV, boolean limited) {
            this.result = result;
            this.depth = depth;
            this.multiPV = multiPV;
            this.limited = limited;
        }
    }

    private static final Map<String, CacheEntry> cache =
            new LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    public static synchronized boolean init(Context context) {
        if (initialised && engine.isReady()) return true;
        initialised = engine.start(context);
        if (!initialised) Log.e(TAG, "Engine failed to start.");
        return initialised;
    }

    private static Context getApplicationContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = activityThreadClass.getMethod("currentApplication");
            return (Context) m.invoke(null);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to get Application Context: " + t.getMessage());
        }
        return null;
    }

    /** Position key: placement, side to move, castling and en passant (move counters ignored). */
    public static String positionKey(String fen) {
        if (fen == null) return null;
        String[] p = fen.trim().split("\\s+");
        if (p.length < 2) return fen;
        StringBuilder sb = new StringBuilder(fen.length());
        sb.append(p[0]).append(' ').append(p[1]);
        sb.append(' ').append(p.length > 2 ? p[2] : "-");
        sb.append(' ').append(p.length > 3 ? p[3] : "-");
        return sb.toString();
    }

    public static List<String> bestMoves(String fen, int depth, int multiPV) {
        return analyze(fen, depth, multiPV).moves;
    }

    /** Returns a cached result for the position if one exists that is at least as deep. */
    public static StockfishProcess.AnalysisResult getCached(String fen, int depth, int multiPV, boolean limited) {
        String key = positionKey(fen);
        if (key == null) return null;
        synchronized (cache) {
            CacheEntry e = cache.get(key);
            if (e != null && e.depth >= depth && e.multiPV >= multiPV && e.limited == limited) {
                return e.result;
            }
        }
        return null;
    }

    private static void putCache(String fen, StockfishProcess.AnalysisResult r, int depth, int multiPV, boolean limited) {
        if (r == null || !r.isValid()) return;
        // Never cache a search that was interrupted before reaching the requested depth.
        if (!r.terminal && !r.hasMate && r.depth < depth) return;
        String key = positionKey(fen);
        if (key == null) return;
        synchronized (cache) {
            CacheEntry old = cache.get(key);
            if (old == null || old.depth <= depth || old.limited != limited) {
                cache.put(key, new CacheEntry(r, depth, multiPV, limited));
            }
        }
    }

    public static void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private static boolean ensureRunning(Context ctx) {
        if (!initialised || !engine.isReady()) {
            Log.w(TAG, "Engine not ready or died. Restarting...");
            initialised = engine.start(ctx);
        }
        return initialised;
    }

    /** Live analysis of a single FEN (honours the Elo limit setting). */
    public static synchronized StockfishProcess.AnalysisResult analyze(String fen, int depth, int multiPV) {
        Context ctx = getApplicationContext();
        if (ctx == null) return StockfishProcess.AnalysisResult.empty();

        boolean limited = StockfishSettings.isLimitStrength(ctx);
        StockfishProcess.AnalysisResult cached = getCached(fen, depth, multiPV, limited);
        if (cached != null) return cached;

        if (!ensureRunning(ctx)) return StockfishProcess.AnalysisResult.empty();
        StockfishProcess.AnalysisResult r = engine.analyze(ctx, fen, null, depth, multiPV, 0, true);
        if (!r.isValid() && !engine.isReady()) {
            // Engine crashed on this position; restart once and retry.
            if (ensureRunning(ctx)) r = engine.analyze(ctx, fen, null, depth, multiPV, 0, true);
        }
        putCache(fen, r, depth, multiPV, limited);
        return r;
    }

    /**
     * Full-strength analysis used by the game review (never Elo-limited).
     *
     * @param baseFen     starting FEN of the game
     * @param moves       game moves leading to the position (may be null)
     * @param positionFen FEN of the analysed position (used for caching / fallback)
     */
    public static synchronized StockfishProcess.AnalysisResult analyzeForReview(
            String baseFen, List<String> moves, String positionFen, int depth, int multiPV, int movetimeMs) {
        Context ctx = getApplicationContext();
        if (ctx == null) return StockfishProcess.AnalysisResult.empty();

        StockfishProcess.AnalysisResult cached = getCached(positionFen, depth, multiPV, false);
        if (cached != null) return cached;

        if (!ensureRunning(ctx)) return StockfishProcess.AnalysisResult.empty();

        StockfishProcess.AnalysisResult r = StockfishProcess.AnalysisResult.empty();
        if (baseFen != null && moves != null) {
            r = engine.analyze(ctx, baseFen, moves, depth, multiPV, movetimeMs, false);
        }
        if (!r.isValid()) {
            // Move history rejected (or not supplied) – fall back to the plain FEN.
            if (!engine.isReady()) ensureRunning(ctx);
            if (positionFen != null && engine.isReady()) {
                r = engine.analyze(ctx, positionFen, null, depth, multiPV, movetimeMs, false);
            }
        }
        putCache(positionFen, r, depth, multiPV, false);
        return r;
    }

    /** Clears the engine hash before reviewing a new game. */
    public static synchronized void newGame() {
        Context ctx = getApplicationContext();
        if (ctx != null && ensureRunning(ctx)) engine.newGame();
    }

    public static String bestMove(String fen, int depth) {
        List<String> moves = bestMoves(fen, depth, 1);
        return moves.isEmpty() ? null : moves.get(0);
    }

    /** Interrupt an ongoing search. Deliberately not synchronized. */
    public static void stopSearch() {
        if (initialised) engine.stopSearch();
    }

    public static synchronized void quit() {
        if (initialised) {
            engine.stop();
            initialised = false;
        }
    }
}
