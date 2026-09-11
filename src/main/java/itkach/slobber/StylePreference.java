package itkach.slobber;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.regex.Pattern;

/**
 * Applies a requested style preference to a served document, so it is
 * already in effect before the response is ever parsed by a client -
 * no client-side JavaScript race between "page rendered" and
 * "preference applied" is possible, because there is nothing left for
 * a client to apply. Two things happen:
 *
 * <ol>
 * <li>The classic "Selectable Alternate Style Sheets" convention (the
 * same one browsers used to natively support via a "page style"
 * picker: {@code <link rel="stylesheet" title="Foo">} for the default,
 * {@code <link rel="alternate stylesheet" title="Bar">} for
 * alternates) is applied directly: any {@code <link>} with a
 * {@code title} attribute and a {@code rel} containing "stylesheet" is
 * a candidate - the one matching the requested title is enabled, all
 * others are disabled. This mirrors exactly the selection semantics
 * of the $styleSwitcher script both aard2-web and aard2-android
 * already use client-side, so it works for any document following
 * that standard convention, not just ones produced by any specific
 * slob-producing tool.
 * <li>The same style is propagated onto every internal link in the
 * document (any {@code href} that isn't a fragment-only reference, a
 * scheme-qualified absolute URL, or protocol-relative - i.e. an
 * ordinary relative link to other content in the same dictionary), so
 * the preference stays in effect as the user follows cross-references
 * from article to article, without any client-side code needing to
 * re-apply anything on every navigation.
 * </ol>
 *
 * Documents that don't use the alternate-stylesheet convention, or
 * that don't offer the requested title, or that have no internal
 * links, are returned unchanged.
 *
 * Parses with jsoup, mutates the matching elements' attributes, and
 * serializes the whole document back out - the ordinary way to edit
 * HTML with a real parser. This is deliberately a full standalone
 * parse ({@link Jsoup#parse(String, String)}), not a fragment parse
 * into a pre-built context: some producers' output isn't a
 * well-formed document (e.g. a stray, attribute-bearing {@code <body>}
 * tag showing up after other content), and a full parse resolves that
 * exactly per the HTML5 tree-construction algorithm - the same
 * algorithm every browser already runs on this same response when it
 * renders it - merging the stray tag's attributes onto the real body
 * element rather than losing them. A fragment-context parse skips that
 * part of the algorithm and silently drops those attributes instead,
 * which is not a trade worth making just to preserve the original
 * document's exact bytes: whatever shape jsoup's serializer settles on
 * is the same shape a browser would already reconstruct from the
 * original bytes anyway.
 */
public class StylePreference {

    private static final Pattern URI_SCHEME =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*:");

    static String apply(String html, String styleTitle, String userStyleHref) {
        Document doc = Jsoup.parse(html, "");
        doc.outputSettings().prettyPrint(false);

        boolean changed = false;
        changed |= applyStylesheetSelection(doc, styleTitle);
        changed |= propagateToInternalLinks(doc, styleTitle);
        changed |= linkUserStyle(doc, userStyleHref);

        return changed ? doc.outerHtml() : html;
    }

    /**
     * Links a user-provided stylesheet as the last {@code <link>} in the
     * {@code <head>} (href points at Slobber's own /user-styles/&lt;name&gt;),
     * so it wins over the document's own stylesheets. A {@code <link>} in the
     * head is render-blocking, so - exactly like an inline {@code <style>} -
     * the page never paints in the default style first; but a link is trivial
     * for a client-side style switcher to add/remove/disable (the same way it
     * toggles the document's built-in {@code <link title>} alternates) and is
     * cacheable across articles rather than re-sent in every response. Doing
     * this server-side means there's no client-side JavaScript race on the
     * initial load - the same reason built-in alternate selection moved here.
     * A user style is mutually exclusive with a built-in one: because its name
     * matches no {@code <link title>}, applyStylesheetSelection above has
     * already disabled every built-in alternate. A client-side switcher that
     * later replaces this style identifies this element by its href (Slobber's
     * own /user-styles/ route) - no marker shared with any particular client is
     * needed.
     */
    private static boolean linkUserStyle(Document doc, String userStyleHref) {
        if (userStyleHref == null || userStyleHref.isEmpty()) {
            return false;
        }
        Element head = doc.head();
        if (head == null) {
            return false;
        }
        head.appendElement("link")
                .attr("rel", "stylesheet")
                .attr("href", userStyleHref);
        return true;
    }

    private static boolean applyStylesheetSelection(Document doc, String styleTitle) {
        boolean changed = false;
        Elements candidates = doc.select("link[title]");
        for (Element link : candidates) {
            if (!hasToken(link.attr("rel"), "stylesheet")) {
                continue;
            }
            String newRel = link.attr("title").equals(styleTitle)
                    ? "stylesheet" : "alternate stylesheet";
            if (newRel.equals(link.attr("rel"))) {
                continue;
            }
            link.attr("rel", newRel);
            changed = true;
        }
        return changed;
    }

    private static boolean propagateToInternalLinks(Document doc, String styleTitle) {
        boolean changed = false;
        Elements anchors = doc.select("a[href]");
        for (Element a : anchors) {
            String href = a.attr("href");
            if (!isInternalLink(href)) {
                continue;
            }
            String newHref = withStyleParam(href, styleTitle);
            if (newHref.equals(href)) {
                continue;
            }
            a.attr("href", newHref);
            changed = true;
        }
        return changed;
    }

    private static boolean isInternalLink(String href) {
        if (href.isEmpty() || href.startsWith("#") || href.startsWith("//")) {
            return false;
        }
        return !URI_SCHEME.matcher(href).find();
    }

    /**
     * Adds or replaces the "style" query parameter on href, inserting it
     * before any fragment. Public so aard2-android can use the same
     * logic to bake a style preference into the very first request URL
     * it constructs (see Application.getUrl(Blob)) - without this, the
     * page loads with the default style first and only gets corrected
     * after the fact by this class's own server-side rewriting, once
     * the user follows a link out of it.
     */
    public static String withStyleParam(String href, String styleTitle) {
        int hashIndex = href.indexOf('#');
        String fragment = hashIndex == -1 ? "" : href.substring(hashIndex);
        String base = hashIndex == -1 ? href : href.substring(0, hashIndex);
        char sep = base.indexOf('?') == -1 ? '?' : '&';
        return base + sep + "style=" + urlEncode(styleTitle) + fragment;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError("UTF-8 is always supported", e);
        }
    }

    private static boolean hasToken(String spaceSeparatedTokens, String token) {
        for (String part : spaceSeparatedTokens.trim().split("\\s+")) {
            if (part.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }
}
