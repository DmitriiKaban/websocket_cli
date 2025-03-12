package com.app;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class App {
    // Set to track visited URLs to prevent redirect loops
    private static Set<String> visitedUrls = new HashSet<>();
    // Maximum number of redirects to follow
    private static final int MAX_REDIRECTS = 5;
    // Redirect counter
    private static int redirectCount = 0;
    // Store search results
    private static Map<Integer, String> searchResults = new HashMap<>();

    public static void main(String[] args) {
        if (args.length < 1) {
            showHelp();
            return;
        }

        switch (args[0]) {
            case "-u":
                if (args.length < 2) {
                    System.out.println("Error: URL required");
                    showHelp();
                    return;
                }
                String url = args[1];
                try {
                    // Reset tracking for each new request
                    visitedUrls.clear();
                    redirectCount = 0;

                    String response = makeHttpRequest(url);
                    String cleanText = parseResponse(response);

                    // Print meaningful content only
                    System.out.println(cleanText);
                } catch (Exception e) {
                    System.out.println("Error making request: " + e.getMessage());
                }
                break;
            case "-s":
                if (args.length < 2) {
                    System.out.println("Error: Search term required");
                    showHelp();
                    return;
                }
                String searchTerm = new ArrayList<>(List.of(args)).subList(1, args.length).stream().reduce((a, b) -> a + " " + b).get();

                try {
                    // Reset tracking for each new request
                    visitedUrls.clear();
                    redirectCount = 0;

                    // Use DuckDuckGo for search (more privacy-friendly)
                    String searchUrl = "https://duckduckgo.com/html/?q=" + URLEncoder.encode(searchTerm, StandardCharsets.UTF_8.toString());
                    String response = makeHttpRequest(searchUrl);
                    displaySearchResults(response);
                } catch (Exception e) {
                    System.out.println("Error performing search: " + e.getMessage());
                }
                break;
            case "-g":
                if (args.length < 2) {
                    System.out.println("Error: Result number required");
                    return;
                }
                try {
                    int resultNum = Integer.parseInt(args[1]);
                    String resultUrl = searchResults.get(resultNum);
                    if (resultUrl != null) {
                        // Reset tracking for each new request
                        visitedUrls.clear();
                        redirectCount = 0;

                        String response = makeHttpRequest(resultUrl);
                        String cleanText = parseResponse(response);
                        System.out.println(cleanText);
                    } else {
                        System.out.println("Error: No search result found with number " + resultNum);
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Error: Invalid result number");
                } catch (Exception e) {
                    System.out.println("Error accessing search result: " + e.getMessage());
                }
                break;
            case "-h":
            default:
                showHelp();
                break;
        }
    }

    private static void showHelp() {
        System.out.println("Usage:");
        System.out.println("  go2web -u <URL>         # make an HTTP request to the specified URL and print the response");
        System.out.println("  go2web -s <search-term> # make an HTTP request to search the term and print top 10 results");
        System.out.println("  go2web -g <number>      # go to the search result with the specified number");
        System.out.println("  go2web -h               # show this help");
    }

    private static String makeHttpRequest(String urlString) throws Exception {
        // Check if we've already visited this URL to prevent loops
        if (visitedUrls.contains(urlString)) {
            throw new Exception("Redirect loop detected");
        }

        // Add URL to visited set
        visitedUrls.add(urlString);

        // Check if we've reached maximum redirects
        if (redirectCount >= MAX_REDIRECTS) {
            throw new Exception("Too many redirects (max: " + MAX_REDIRECTS + ")");
        }

        URL url = new URL(urlString);

        // Determine if we need to use HTTPS
        boolean isHttps = url.getProtocol().equalsIgnoreCase("https");
        int port = url.getPort() != -1 ? url.getPort() : (isHttps ? 443 : 80);

        Socket socket;
        if (isHttps) {
            // Create HTTPS connection using SSLSocketFactory
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = factory.createSocket(url.getHost(), port);
        } else {
            // Create regular HTTP connection
            socket = new Socket(url.getHost(), port);
        }

        // Prepare request
        String path = url.getPath().isEmpty() ? "/" : url.getPath();
        if (url.getQuery() != null) {
            path += "?" + url.getQuery();
        }

        String request =
                "GET " + path + " HTTP/1.1\r\n" +
                        "Host: " + url.getHost() + "\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml\r\n" +
                        "Accept-Language: en-US,en;q=0.9\r\n" +
                        "Connection: close\r\n\r\n";

        // Send request
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes());
        out.flush();

        // Read response
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            response.write(buffer, 0, bytesRead);
        }

        socket.close();

        String responseStr = response.toString(StandardCharsets.UTF_8.name());

        // Check for redirect
        String[] lines = responseStr.split("\r\n");
        if (lines.length > 0) {
            String statusLine = lines[0];
            if (statusLine.contains("301") || statusLine.contains("302") || statusLine.contains("307") || statusLine.contains("308")) {
                String location = null;
                for (String line : lines) {
                    if (line.toLowerCase().startsWith("location:")) {
                        location = line.substring("location:".length()).trim();
                        break;
                    }
                }

                if (location != null) {
                    // Convert relative URL to absolute if needed
                    if (location.startsWith("/")) {
                        location = url.getProtocol() + "://" + url.getHost() + location;
                    } else if (!location.startsWith("http")) {
                        // Handle relative URLs without leading slash
                        String baseUrl = url.getProtocol() + "://" + url.getHost();
                        if (url.getPath().length() > 0) {
                            // Get the directory part of the path
                            String directory = url.getPath();
                            if (!directory.endsWith("/")) {
                                directory = directory.substring(0, directory.lastIndexOf('/') + 1);
                            }
                            baseUrl += directory;
                        } else {
                            baseUrl += "/";
                        }
                        location = baseUrl + location;
                    }

                    redirectCount++;
                    System.out.println("Following redirect #" + redirectCount + " to: " + location);
                    return makeHttpRequest(location);
                }
            }
        }

        return responseStr;
    }

    private static String parseResponse(String response) {
        // Extract headers and body
        int headerEnd = response.indexOf("\r\n\r\n");
        if (headerEnd == -1) {
            return "Invalid response format";
        }

        String body = response.substring(headerEnd + 4);

        // Remove document type declaration
        body = body.replaceAll("<!DOCTYPE[^>]*>", "");

        // Remove comments
        body = body.replaceAll("<!--.*?-->", "");

        // Remove script tags and their content
        body = body.replaceAll("(?s)<script.*?</script>", "");

        // Remove style tags and their content
        body = body.replaceAll("(?s)<style.*?</style>", "");

        // Remove CSS and other attributes
        body = body.replaceAll("style=\"[^\"]*\"", "");
        body = body.replaceAll("class=\"[^\"]*\"", "");
        body = body.replaceAll("id=\"[^\"]*\"", "");

        // Remove head section
        body = body.replaceAll("(?s)<head>.*?</head>", "");

        // Remove navigation menus
        body = body.replaceAll("(?s)<nav.*?</nav>", "");
        body = body.replaceAll("(?s)<header.*?</header>", "");
        body = body.replaceAll("(?s)<footer.*?</footer>", "");

        // Remove form elements
        body = body.replaceAll("(?s)<form.*?</form>", "");

        // Remove all other HTML tags but keep their content
        body = body.replaceAll("<[^>]*>", "");

        // Replace HTML entities
        body = body.replaceAll("&nbsp;", " ");
        body = body.replaceAll("&lt;", "<");
        body = body.replaceAll("&gt;", ">");
        body = body.replaceAll("&amp;", "&");
        body = body.replaceAll("&quot;", "\"");

        // Remove excess whitespace
        body = body.replaceAll("\\s+", " ");

        // Remove leading/trailing whitespace
        body = body.trim();

        // Split into lines and filter out empty or meaningless lines
        String[] lines = body.split("\\. ");
        StringBuilder meaningfulContent = new StringBuilder();

        for (String line : lines) {
            // Skip lines that are too short or just contain non-useful characters
            line = line.trim();
            if (line.length() > 3 && !line.matches("[\\s\\d\\W]+")) {
                meaningfulContent.append(line).append(". ");
            }
        }

        return meaningfulContent.toString().trim();
    }

    private static void displaySearchResults(String response) {
        // Clear previous search results
        searchResults.clear();

        // Extract the body content
        int headerEnd = response.indexOf("\r\n\r\n");
        if (headerEnd == -1) {
            System.out.println("Invalid response format");
            return;
        }

        String body = response.substring(headerEnd + 4);

        // For DuckDuckGo search results
        Pattern linkPattern = Pattern.compile("<a class=\"result__a\" href=\"([^\"]+)\"[^>]*>([^<]+)</a>");
        Matcher matcher = linkPattern.matcher(body);

        int count = 0;
        Set<String> uniqueUrls = new HashSet<>();

        System.out.println("Search Results:");
        System.out.println("===============");

        while (matcher.find() && count < 10) {
            String url = matcher.group(1);
            String title = matcher.group(2).trim();

            // DuckDuckGo sometimes uses redirects, try to extract the real URL
            if (url.startsWith("/l/?")) {
                Pattern uddgPattern = Pattern.compile("uddg=([^&]+)");
                Matcher uddgMatcher = uddgPattern.matcher(url);
                if (uddgMatcher.find()) {
                    try {
                        url = URLDecoder.decode(uddgMatcher.group(1), StandardCharsets.UTF_8.toString());
                    } catch (UnsupportedEncodingException e) {
                        // Use the original URL if decoding fails
                    }
                }
            }

            // Make sure URL is absolute
            if (!url.startsWith("http")) {
                url = "https://duckduckgo.com" + url;
            }

            // Add to results if unique
            if (uniqueUrls.add(url)) {
                count++;
                searchResults.put(count, url);
                System.out.println(count + ". " + title);
                System.out.println("   " + url);
                System.out.println();
            }
        }

        // If we didn't find any results with the preferred pattern, try a more generic pattern
        if (count == 0) {
            // Try a more generic pattern for links
            Pattern genericPattern = Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>");
            Matcher genericMatcher = genericPattern.matcher(body);

            while (genericMatcher.find() && count < 10) {
                String url = genericMatcher.group(1);
                String title = genericMatcher.group(2).trim();

                // Skip internal links, images, etc.
                if (url.startsWith("#") || url.startsWith("javascript:") ||
                        url.endsWith(".jpg") || url.endsWith(".png") || url.endsWith(".gif") ||
                        title.isEmpty() || title.length() < 5) {
                    continue;
                }

                // Make sure URL is absolute
                if (!url.startsWith("http")) {
                    url = "https://duckduckgo.com" + url;
                }

                // Add to results if unique
                if (uniqueUrls.add(url)) {
                    count++;
                    searchResults.put(count, url);
                    System.out.println(count + ". " + title);
                    System.out.println("   " + url);
                    System.out.println();
                }
            }
        }

        if (count == 0) {
            System.out.println("No search results found. The search engine page may have changed its structure.");
        } else {
            System.out.println("Use 'go2web -g <number>' to access any of the search results.");
        }
    }
}