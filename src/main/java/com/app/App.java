package com.app;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class App {

    private static Set<String> visitedUrls = new HashSet<>();
    private static final int MAX_REDIRECTS = 5;
    private static int redirectCount = 0;
    private static Map<Integer, String> searchResults = new HashMap<>();
    private static Map<String, String> cache = new HashMap<>();
    private static final String CACHE_FILE = "cache.txt";

    public static void main(String[] args) {

        loadCache();

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

                cache.keySet().forEach(System.out::println);
                if (cache.containsKey(url)) {
                    System.out.println("Cached response:");
                    System.out.println(cache.get(url));
                    return;
                }

                try {
                    // Reset tracking for each new request
                    visitedUrls.clear();
                    redirectCount = 0;

                    String response = makeHttpRequest(url);

                    if (response.contains("application/json")) {
                        response = makeHttpRequest(url, true);
                    }

                    String cleanText = parseResponse(response);

                    cache.put(url, cleanText);
                    saveCache();

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

    private static void saveCache() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(CACHE_FILE))) {
            for (Map.Entry<String, String> entry : cache.entrySet()) {
                writer.write(entry.getKey());
                writer.write("+++++++"); // Split between key and value
                writer.write(entry.getValue());
                writer.newLine();
                writer.write("===================================="); // Split between entries
                writer.newLine();
            }
            System.out.println("Cache saved successfully.");
        } catch (IOException e) {
            System.out.println("Failed to save cache: " + e.getMessage());
        }
    }

    private static void loadCache() {
        try (BufferedReader reader = new BufferedReader(new FileReader(CACHE_FILE))) {
            String line;
            StringBuilder contentBuilder = new StringBuilder();
            String url = null;

            while ((line = reader.readLine()) != null) {
                if (line.equals("====================================")) {
                    if (url != null) {
                        cache.put(url, contentBuilder.toString().trim());
                    }
                    url = null;
                    contentBuilder.setLength(0);
                } else if (line.contains("+++++++")) {
                    String[] parts = line.split("\\+{7}", 2);
                    if (parts.length == 2) {
                        url = parts[0];
                        contentBuilder.append(parts[1]).append("\n");
                    }
                } else if (url != null) {
                    contentBuilder.append(line).append("\n");
                }
            }

            if (url != null) {
                cache.put(url, contentBuilder.toString().trim());
            }

            System.out.println("Cache loaded successfully.");
        } catch (IOException e) {
            System.out.println("No existing cache found.");
            createCacheFile();
        }
    }

    private static void createCacheFile() {
        try {
            File file = new File(CACHE_FILE);
            if (file.createNewFile()) {
                System.out.println("Cache file created: " + file.getName());
            } else {
                System.out.println("Cache file already exists.");
            }
        } catch (IOException e) {
            System.out.println("An error occurred while creating cache file.");
            e.printStackTrace();
        }
    }

    private static String makeHttpRequest(String urlString, boolean acceptJson) throws Exception {

        if (cache.containsKey(urlString)) {
            System.out.println("Returning cached response for: " + urlString);
            return cache.get(urlString);
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

        String acceptHeader = acceptJson ? "application/json" : "text/html,application/xhtml+xml,application/xml";

        String request =
                "GET " + path + " HTTP/1.1\r\n" +
                        "Host: " + url.getHost() + "\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36\r\n" +
                        "Accept: " + acceptHeader + "\r\n" +
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

    private static String makeHttpRequest(String urlString) throws Exception {

        visitedUrls.clear();
        redirectCount = 0;
        return makeHttpRequest(urlString, false);
    }

    private static String parseResponse(String response) {
        // Extract headers and body
        int headerEnd = response.indexOf("\r\n\r\n");
        if (headerEnd == -1) {
            return "Invalid response format";
        }

        String headers = response.substring(0, headerEnd);
        String body = response.substring(headerEnd + 4);

        String contentType = "";
        Pattern contentTypePattern = Pattern.compile("Content-Type:\\s*([^;\\r\\n]+)", Pattern.CASE_INSENSITIVE);
        Matcher contentTypeMatcher = contentTypePattern.matcher(headers);
        if (contentTypeMatcher.find()) {
            contentType = contentTypeMatcher.group(1).trim();
        }

        // Handle different content types
        if (contentType.equals("application/json")) {
            // Return JSON as is
            return body;
        } else {
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
                        title.length() < 5) {
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
        }


        if (count == 10) {
            System.out.print("Choose the URL you want to visit by typing its number: ");
            Scanner scanner = new Scanner(System.in);
            int choice = scanner.nextInt();
            String resultUrl = searchResults.get(choice);
            if (resultUrl != null) {
                visitedUrls.clear();
                redirectCount = 0;

                String response1;

                cache.keySet().forEach(System.out::println);
                if (cache.containsKey(resultUrl)) {
                    System.out.println("Returning cached response for: " + resultUrl);
                    System.out.println(cache.get(resultUrl));
                    return;
                }

                try {
                    System.out.println("Fetching from server: " + resultUrl);
                    response1 = makeHttpRequest(resultUrl);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                String cleanText = parseResponse(response1);
                cache.put(resultUrl, cleanText);
                saveCache();
                System.out.println(cleanText);

            } else {
                System.out.println("Error: No search result found with number " + choice);
            }
        }
    }
}