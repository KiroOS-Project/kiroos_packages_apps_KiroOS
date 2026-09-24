/*
 * Copyright (C) 2026 KiroOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kiroos.kai;

import android.net.Uri;
import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * kAI's web search: a model only knows the world up to its training, so questions go to the
 * web first (DuckDuckGo, no account or key) and the answer is written from the results.
 * The model gets the result snippets plus the most relevant paragraphs of the first page.
 */
final class WebSearch {

    static final class Result {
        final String title;
        final String url;
        final String snippet;

        Result(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }

        /** "sammobile.com" */
        String site() {
            final String host = Uri.parse(url).getHost();
            if (host == null) {
                return url;
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        }
    }

    private static final String ENDPOINT = "https://html.duckduckgo.com/html/";
    private static final String AGENT = "Mozilla/5.0 (Linux; Android 7.1.2; KiroOS) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final Pattern RESULT = Pattern.compile(
            "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>(.*?)(?=class=\"result__a\"|$)",
            Pattern.DOTALL);
    private static final Pattern SNIPPET = Pattern.compile(
            "class=\"result__snippet\"[^>]*>(.*?)</(?:a|div|td)>", Pattern.DOTALL);
    private static final Pattern PARAGRAPH = Pattern.compile("<p[^>]*>(.*?)</p>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE = Pattern.compile(
            "<(script|style|noscript)[^>]*>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    /** questions that never need the web: greetings, thanks, writing and transforming text */
    private static final Pattern NO_WEB = Pattern.compile(
            "^(ciao|salve|hey|hi|hello|ehi|buongiorno|buonasera|buonanotte|grazie|thanks|"
                    + "thank you|ok|okay|va bene|perfetto|chi sei|cosa sai fare|come stai|"
                    + "how are you|who are you)\\b.{0,20}$"
                    + "|^(scrivi|scrivimi|riscrivi|traduci|riassumi|correggi|calcola|spiegami il "
                    + "codice|write|rewrite|translate|summari[sz]e|correct|calculate)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private WebSearch() {
    }

    /** true when the question is about facts the web knows better than the model */
    static boolean wanted(String text) {
        final String t = text.trim();
        return t.length() >= 6 && !NO_WEB.matcher(t).matches() && !MATH.matcher(t).find()
                && !CODE.matcher(t).find();
    }

    /** arithmetic ("17 per 23", "quanto fa 3+4", "radice di 81"): the model's own job */
    private static final Pattern MATH = Pattern.compile(
            "\\d\\s*([x×*/:+\\-^%]|per|diviso|pi[uù]|meno|times|plus|minus|divided by)\\s*\\d"
                    + "|\\b(quanto fa|radice( quadrata)?( di)?|percentuale|equazione|derivata|"
                    + "integrale|what is \\d|square root|equation)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** writing or explaining code needs no search either */
    private static final Pattern CODE = Pattern.compile(
            "\\b(codice|funzione|script|python|java|kotlin|javascript|html|css|sql|bash|"
                    + "c\\+\\+|algoritmo|code|function|regex)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern ITALIAN = Pattern.compile(
            "\\b(il|lo|gli|che|di|del|della|dei|è|sono|quando|come|perch[eé]|cosa|quale|"
                    + "quali|quanto|quanti|dove|chi|uscit[aoie]|nuov[aoie]|ultim[aoie]|"
                    + "puoi|fammi|dimmi|ciao|grazie)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** a rough guess, enough to pick the language of the model's instructions */
    static boolean looksItalian(String text) {
        final Matcher m = ITALIAN.matcher(text);
        int hits = 0;
        while (m.find()) {
            hits++;
        }
        return hits >= 1 && (hits >= 2 || text.split("\\s+").length <= 6);
    }

    /** Blocking. Up to max results (lang picks the region), empty when the search fails. */
    static List<Result> search(String query, int max, Locale lang) {
        final List<Result> out = new ArrayList<>();
        try {
            final String region = "it".equals(lang.getLanguage()) ? "it-it" : "wt-wt";
            final String body = "q=" + URLEncoder.encode(query, "UTF-8") + "&kl=" + region;
            final HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            try {
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("User-Agent", AGENT);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
                if (c.getResponseCode() != 200) {
                    return out;
                }
                final String html = read(c.getInputStream(), 400 * 1024, StandardCharsets.UTF_8);
                final Matcher m = RESULT.matcher(html);
                final Set<String> seen = new HashSet<>();
                while (m.find() && out.size() < max) {
                    final String url = realUrl(unescape(m.group(1)));
                    if (url == null || !seen.add(url)) {
                        continue;
                    }
                    final Matcher s = SNIPPET.matcher(m.group(3));
                    out.add(new Result(text(m.group(2)), url, s.find() ? text(s.group(1)) : ""));
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            // offline or blocked: the model answers on its own
        }
        return out;
    }

    /** result links are direct, or DuckDuckGo redirects (uddg=…); ads are dropped */
    private static String realUrl(String href) {
        if (href.startsWith("//")) {
            href = "https:" + href;
        }
        final Uri u = Uri.parse(href);
        final String host = u.getHost();
        if (host != null && host.endsWith("duckduckgo.com")) {
            final String target = u.getQueryParameter("uddg");
            if (target == null || u.getPath() == null || !u.getPath().startsWith("/l/")) {
                return null; // an ad (y.js) or an internal link
            }
            href = target;
        }
        return href.startsWith("http") ? href : null;
    }

    /**
     * Blocking. The paragraphs of the page that share most words with the question, in page
     * order, up to max characters; "" when the page cannot be read.
     */
    static String excerpt(String url, String question, int max) {
        try {
            final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(5000);
                c.setReadTimeout(6000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", AGENT);
                c.setRequestProperty("Accept-Language",
                        Locale.getDefault().getLanguage() + ",en;q=0.7");
                if (c.getResponseCode() != 200) {
                    return "";
                }
                final String type = c.getContentType();
                if (type != null && !type.contains("html")) {
                    return "";
                }
                Charset cs = StandardCharsets.UTF_8;
                if (type != null && type.toLowerCase(Locale.ROOT).contains("charset=")) {
                    try {
                        cs = Charset.forName(type.substring(
                                type.toLowerCase(Locale.ROOT).indexOf("charset=") + 8).trim());
                    } catch (Exception e) {
                        // keep UTF-8
                    }
                }
                final String html = NOISE.matcher(read(c.getInputStream(), 600 * 1024, cs))
                        .replaceAll(" ");
                final Set<String> keys = words(question);
                final List<String> paragraphs = new ArrayList<>();
                final List<Integer> scores = new ArrayList<>();
                final Matcher m = PARAGRAPH.matcher(html);
                while (m.find()) {
                    final String p = text(m.group(1));
                    if (p.length() < 60) {
                        continue;
                    }
                    int score = 0;
                    for (String w : words(p)) {
                        if (keys.contains(w)) {
                            score++;
                        }
                    }
                    paragraphs.add(p);
                    scores.add(score);
                }
                // keep the best paragraphs (ties: earlier first) until max, then page order
                final boolean[] keep = new boolean[paragraphs.size()];
                int used = 0;
                while (true) {
                    int best = -1;
                    for (int i = 0; i < paragraphs.size(); i++) {
                        if (!keep[i] && (best < 0 || scores.get(i) > scores.get(best))) {
                            best = i;
                        }
                    }
                    if (best < 0 || used + paragraphs.get(best).length() > max) {
                        break;
                    }
                    keep[best] = true;
                    used += paragraphs.get(best).length();
                }
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < paragraphs.size(); i++) {
                    if (keep[i]) {
                        sb.append(paragraphs.get(i)).append('\n');
                    }
                }
                if (sb.length() == 0 && !paragraphs.isEmpty()) {
                    final String p = paragraphs.get(0);
                    sb.append(p, 0, Math.min(p.length(), max));
                }
                return sb.toString().trim();
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The question as the model sees it: the numbered results (and the page excerpt), then
     * the question, labelled in the phone's language. Short and plain: a 0.5B model answers
     * well from this, and gets lost in longer instructions (measured on the S6).
     */
    static String prompt(String question, List<Result> results, String excerpt,
            String resultsLabel, String questionLabel) {
        final StringBuilder sb = new StringBuilder();
        sb.append(resultsLabel).append(":\n");
        for (int i = 0; i < results.size(); i++) {
            final Result r = results.get(i);
            sb.append('[').append(i + 1).append("] ").append(r.title).append(" (")
                    .append(r.site()).append(")");
            if (!r.snippet.isEmpty()) {
                sb.append(": ").append(r.snippet);
            }
            sb.append('\n');
        }
        if (!excerpt.isEmpty()) {
            sb.append("\n[1]:\n").append(excerpt).append('\n');
        }
        sb.append('\n').append(questionLabel).append(": ").append(question);
        return sb.toString();
    }

    /** "23 settembre 2026" */
    static String today(Locale lang) {
        return DateFormat.getDateInstance(DateFormat.LONG, lang).format(new Date());
    }

    /** the sources line under an answer: "🔎 [1] site · [2] site" as Markdown links */
    static String sources(List<Result> results) {
        final StringBuilder sb = new StringBuilder(KaiActivity.SOURCES_MARK);
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                sb.append(" · ");
            }
            sb.append('[').append(i + 1).append(' ').append(results.get(i).site().replace("]",
                    "")).append("](").append(results.get(i).url.replace(")", "%29")).append(')');
        }
        return sb.toString();
    }

    private static Set<String> words(String s) {
        final Set<String> out = new HashSet<>();
        final Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static String text(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
                .replaceAll("\\s+", " ").trim();
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"");
    }

    private static String read(InputStream in, int limit, Charset cs) throws Exception {
        try (InputStream i = in) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while (bytes.size() < limit && (n = i.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            return new String(bytes.toByteArray(), cs);
        }
    }
}
