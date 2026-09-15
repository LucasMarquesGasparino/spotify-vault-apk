package com.spotifyvault.app;

/**
 * Consultas SQLite puras (sem dependência Android) para permitir teste unitário.
 * Alto risco mitigado: busca/ordenação no SQL em vez de carregar 2000 linhas no JS.
 */
public final class PlaysQuery {
    private PlaysQuery() {}

    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_LIMIT = 20;

    public static int clampLimit(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) return MAX_LIMIT;
        return limit;
    }

    public static int clampOffset(int offset) {
        return Math.max(0, offset);
    }

    /** Escapa LIKE com ESCAPE '\': \, %, _ */
    public static String escapeLike(String q) {
        if (q == null) return "";
        StringBuilder sb = new StringBuilder(q.length() + 4);
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '\\' || c == '%' || c == '_') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    public static final class Built {
        public final String sql;
        public final String[] args;
        /** true = chamador deve usar fallback JS (agregação plays/skipped) */
        public final boolean fallbackJs;
        Built(String sql, String[] args, boolean fallbackJs) {
            this.sql = sql;
            this.args = args;
            this.fallbackJs = fallbackJs;
        }
    }

    /**
     * Monta SELECT paginado com filtro LIKE e ORDER BY indexado.
     * sort: recent | artist. plays|skipped exigem GROUP BY -> fallbackJs=true.
     * Colunas sem raw_json para payload menor.
     */
    public static Built buildSearch(String query, String sort, int limit, int offset) {
        int lim = clampLimit(limit);
        int off = clampOffset(offset);
        String s = sort == null ? "recent" : sort;
        boolean fallback = s.equals("plays") || s.equals("skipped");

        String orderBy;
        if (s.equals("artist")) {
            orderBy = "ORDER BY artist_names COLLATE NOCASE ASC, played_at_ms DESC ";
        } else {
            orderBy = "ORDER BY played_at_ms DESC ";
        }

        String cols = "SELECT played_at, track_id, track_name, artist_names, album_name, album_image, "
                + "duration_ms, explicit, context_type, context_uri, ms_played, skipped, reason_end, played_at_ms FROM plays ";
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) {
            return new Built(cols + orderBy + "LIMIT ? OFFSET ?",
                    new String[]{String.valueOf(lim), String.valueOf(off)}, fallback);
        }
        String esc = escapeLike(q);
        String like = "%" + esc + "%";
        String sql = cols + "WHERE (lower(track_name) LIKE ? ESCAPE '\\' "
                + "OR lower(artist_names) LIKE ? ESCAPE '\\' "
                + "OR lower(album_name) LIKE ? ESCAPE '\\') "
                + orderBy + "LIMIT ? OFFSET ?";
        return new Built(sql, new String[]{like, like, like, String.valueOf(lim), String.valueOf(off)}, fallback);
    }

    public static final class BuiltCount {
        public final String sql;
        public final String[] args;
        BuiltCount(String sql, String[] args) { this.sql = sql; this.args = args; }
    }

    /** COUNT(*) com o mesmo WHERE do buildSearch (paridade testada). */
    public static BuiltCount buildCount(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) {
            return new BuiltCount("SELECT COUNT(*) FROM plays", new String[]{});
        }
        String like = "%" + escapeLike(q) + "%";
        String sql = "SELECT COUNT(*) FROM plays WHERE (lower(track_name) LIKE ? ESCAPE '\\' "
                + "OR lower(artist_names) LIKE ? ESCAPE '\\' "
                + "OR lower(album_name) LIKE ? ESCAPE '\\')";
        return new BuiltCount(sql, new String[]{like, like, like});
    }

    /** Converte yyyy-MM-dd (BRT) em [startMs, endMs). Puro para teste. */
    public static long[] dayBoundsBRT(String yyyyMMdd) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
            sdf.setLenient(false);
            java.util.Date d = sdf.parse(yyyyMMdd);
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
            cal.setTime(d);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            long start = cal.getTimeInMillis();
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            return new long[]{start, cal.getTimeInMillis()};
        } catch (Exception e) {
            return new long[]{0, Long.MAX_VALUE};
        }
    }
}
