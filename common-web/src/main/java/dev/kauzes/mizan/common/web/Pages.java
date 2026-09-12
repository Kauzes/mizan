package dev.kauzes.mizan.common.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Where a page sits in an answer, said in headers rather than in the body.
 *
 * <p>An envelope would be tidier to write and would break every client parsing a list, which
 * matters for a contract this platform publishes and asks people to build against. So a paged
 * list stays the array it always was, and this is what carries the rest.
 *
 * <p>Shared because two services now page, and two services describing the same thing with
 * different header names is a client that has to know which service it is talking to.
 */
public final class Pages {

    /** How many there are in total, not how many are in this page. */
    public static final String TOTAL = "X-Total-Count";

    public static final String PAGE = "X-Page";
    public static final String SIZE = "X-Page-Size";

    private Pages() {
    }

    /**
     * Adds the paging headers, and links to the pages either side where there are any.
     *
     * @param uris the request's own URI, which the links are built from, so a client follows
     *     them without having to rebuild the filters it sent
     */
    public static void describe(
            HttpHeaders headers,
            UriComponentsBuilder uris,
            long total,
            int page,
            int size) {

        headers.set(TOTAL, String.valueOf(total));
        headers.set(PAGE, String.valueOf(page));
        headers.set(SIZE, String.valueOf(size));

        List<String> links = new ArrayList<>();
        if ((long) (page + 1) * size < total) {
            links.add(link(uris, page + 1, size, "next"));
        }
        if (page > 0) {
            links.add(link(uris, page - 1, size, "prev"));
        }
        if (!links.isEmpty()) {
            headers.set(HttpHeaders.LINK, String.join(", ", links));
        }
    }

    private static String link(UriComponentsBuilder uris, int page, int size, String relation) {
        String url = uris.replaceQueryParam("page", page)
                .replaceQueryParam("size", size)
                .build()
                .toUriString();
        return "<" + url + ">; rel=\"" + relation + "\"";
    }
}
