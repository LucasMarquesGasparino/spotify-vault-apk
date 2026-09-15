import java.util.Arrays;
import java.util.List;

/** Teste consistente (sem Android/JUnit): javac + java apenas. */
public class PlaysQueryTest {
    static int pass = 0, fail = 0;

    static void check(boolean cond, String name) {
        if (cond) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] a) {
        // clamp
        check(com.spotifyvault.app.PlaysQuery.clampLimit(0) == 20, "clampLimit(0)=20");
        check(com.spotifyvault.app.PlaysQuery.clampLimit(500) == 100, "clampLimit(500)=100");
        check(com.spotifyvault.app.PlaysQuery.clampLimit(20) == 20, "clampLimit(20)=20");
        check(com.spotifyvault.app.PlaysQuery.clampOffset(-5) == 0, "clampOffset(-5)=0");

        // escapeLike
        check(com.spotifyvault.app.PlaysQuery.escapeLike("a%b_c\\d").equals("a\\%b\\_c\\\\d"), "escapeLike");
        check(com.spotifyvault.app.PlaysQuery.escapeLike(null).equals(""), "escapeLike null");

        // buildSearch sem query
        com.spotifyvault.app.PlaysQuery.Built b1 =
                com.spotifyvault.app.PlaysQuery.buildSearch("", "recent", 20, 0);
        check(!b1.fallbackJs && b1.sql.contains("ORDER BY played_at_ms DESC") && b1.args.length == 2, "search vazio recent");
        check(!b1.sql.contains("LIKE"), "search vazio sem LIKE");
        check(!b1.sql.toLowerCase().contains("raw_json"), "sem raw_json no payload");

        // buildSearch com query recent
        com.spotifyvault.app.PlaysQuery.Built b2 =
                com.spotifyvault.app.PlaysQuery.buildSearch("Daft Punk", "recent", 20, 40);
        check(b2.args.length == 5, "search args=5");
        check(b2.args[0].equals("%daft punk%"), "like lower");
        check(b2.sql.contains("ESCAPE"), "escape clause");
        check(b2.sql.contains("LIMIT ? OFFSET ?"), "paginacao");
        check(Arrays.asList(b2.args).get(3).equals("20") && Arrays.asList(b2.args).get(4).equals("40"), "limit/offset");

        // artist ordena por nome
        com.spotifyvault.app.PlaysQuery.Built b3 =
                com.spotifyvault.app.PlaysQuery.buildSearch("", "artist", 20, 0);
        check(b3.sql.contains("artist_names"), "artist order");

        // plays/skipped -> fallback JS (GROUP BY de alto custo fica no caminho antigo)
        check(com.spotifyvault.app.PlaysQuery.buildSearch("", "plays", 20, 0).fallbackJs, "plays fallback");
        check(com.spotifyvault.app.PlaysQuery.buildSearch("", "skipped", 20, 0).fallbackJs, "skipped fallback");
        check(!com.spotifyvault.app.PlaysQuery.buildSearch("", "recent", 20, 0).fallbackJs, "recent sem fallback");

        // limite grampeado no SQL
        com.spotifyvault.app.PlaysQuery.Built b4 =
                com.spotifyvault.app.PlaysQuery.buildSearch("x", "recent", 5000, -3);
        check(b4.args[3].equals("100") && b4.args[4].equals("0"), "clamp no builder");

        // dayBoundsBRT: 24h exatas e start<end
        long[] bounds = com.spotifyvault.app.PlaysQuery.dayBoundsBRT("2026-09-02");
        check(bounds[1] - bounds[0] == 24L * 3600 * 1000, "dayBounds 24h");
        check(bounds[0] < bounds[1] && bounds[0] > 0, "dayBounds ordem");
        long[] bad = com.spotifyvault.app.PlaysQuery.dayBoundsBRT("invalida");
        check(bad[0] == 0 && bad[1] == Long.MAX_VALUE, "dayBounds fallback");

        // LIKE injection: % e _ do usuário não viram wildcard
        com.spotifyvault.app.PlaysQuery.Built b5 =
                com.spotifyvault.app.PlaysQuery.buildSearch("100%_x", "recent", 20, 0);
        check(b5.args[0].equals("%100\\%\\_x%"), "like injection escapado");

        // buildCount paridade com buildSearch
        com.spotifyvault.app.PlaysQuery.BuiltCount c1 =
                com.spotifyvault.app.PlaysQuery.buildCount("");
        check(c1.sql.equals("SELECT COUNT(*) FROM plays") && c1.args.length == 0, "count vazio");
        com.spotifyvault.app.PlaysQuery.BuiltCount c2 =
                com.spotifyvault.app.PlaysQuery.buildCount("Daft Punk");
        check(c2.args.length == 3 && c2.args[0].equals("%daft punk%"), "count like");
        check(c2.sql.contains("lower(track_name)") && c2.sql.contains("lower(artist_names)")
                && c2.sql.contains("lower(album_name)"), "count parity WHERE");
        com.spotifyvault.app.PlaysQuery.BuiltCount c3 =
                com.spotifyvault.app.PlaysQuery.buildCount("100%_x");
        check(c3.args[0].equals("%100\\%\\_x%"), "count injection escapado");

        System.out.println("PlaysQueryTest: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
