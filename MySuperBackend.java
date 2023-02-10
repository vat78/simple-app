import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;

/**
 * This is a simple example of a backend server that provides currency exchange rates.
 */
public class MySuperBackend {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        var apiContext = server.createContext("/api", new DataProvider());
        apiContext.getFilters().add(new RequestParameterParser());
        server.setExecutor(new ForkJoinPool(8));
        server.start();
    }

    /**
     * This is a simple HTTP filter that extracts query parameters from the url.
     */
    private static class RequestParameterParser extends Filter {
        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            var id = UUID.randomUUID().toString();
            exchange.setAttribute("id", id);
            log("Request " + id + " received from " + exchange.getRemoteAddress());
            var query = exchange.getRequestURI().getQuery();
            if (query != null) {
                var params = query.split("&");
                for (var param : params) {
                    var split = param.split("=");
                    exchange.setAttribute(split[0], split[1]);
                }
            }
            chain.doFilter(exchange);
        }

        @Override
        public String description() {
            return "Parses the request parameters";
        }
    }

    /**
     * This is a simple HTTP handler that gets data  from external API and builds the response.
     */
    private static class DataProvider implements HttpHandler {
        private static final String API_KEY = System.getenv("API_KEY");

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            var currency = exchange.getAttribute("currency");
            if (currency == null) {
                handleAllCurrencies(exchange);
            } else {
                handleCurrency(exchange, currency);
            }
        }

        private void handleCurrency(HttpExchange exchange, Object currency) throws IOException {
            try {
                var data = getCurrencyData(currency.toString().toUpperCase());
                successResponse(exchange, "[" + data + "]");
            } catch (Exception e) {
                responseWithError(exchange, 500, "Unexpected error");
            }
        }

        private void handleAllCurrencies(HttpExchange exchange) throws IOException {
            var response = "[";
            try {
                for (var symbol : List.of("USD", "EUR", "GBP", "CNY", "BTC")) {
                    var data = getCurrencyData(symbol);
                    response += data + ",";
                }
                response = response.substring(0, response.length() - 1) + "]";
                successResponse(exchange, response);
            } catch (Exception ex) {
                responseWithError(exchange, 500, "Unexpected error");
            }
        }

        private void successResponse(HttpExchange exchange, String response) throws IOException {
            log("Request " + exchange.getAttribute("id") + " completed");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
        private void responseWithError(HttpExchange exchange, int code, String message) throws IOException {
            log("Request " + exchange.getAttribute("id") + " failed with code " + code);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(code, message.length());
            OutputStream os = exchange.getResponseBody();
            os.write(message.getBytes());
            os.close();
        }

        private CurrencyData getCurrencyData(String currency) throws Exception {
            var url = new StringBuilder()
                    .append("https://www.alphavantage.co/query?function=CURRENCY_EXCHANGE_RATE&from_currency=")
                    .append(currency)
                    .append("&to_currency=RUB&apikey=")
                    .append(API_KEY)
                    .toString();
            var request = HttpRequest.newBuilder(new URI(url)).GET().build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            var values = new Parser().parseJsonData(response.body());
            return new CurrencyData(values.get("time"),
                    values.get("fromCurrency"),
                    values.get("toCurrency"),
                    values.get("bid"),
                    values.get("ask"));
        }
    }

    /**
     * Ugly JSON parser.
     */
    private static class Parser {
        private static final Pattern PATTERN = Pattern.compile("(\\\".*\\\")");

        public Map<String, String> parseJsonData(String data) {
            var split = Arrays.stream(data.split("\n"))
                    .map(s -> s.split("\":"))
                    .filter(arr -> arr.length >= 2)
                    .flatMap(Arrays::stream)
                    .toArray(String[]::new);
            var values = new HashMap<String, String>();
            for (int i=0; i<split.length; i=i+2) {
                switch (split[i].trim()) {
                    case "\"6. Last Refreshed" -> values.put("time", extractValueFromBrackets(split[i+1]));
                    case "\"7. Time Zone" -> values.put("timeZone", extractValueFromBrackets(split[i+1]));
                    case "\"1. From_Currency Code" -> values.put("fromCurrency", extractValueFromBrackets(split[i+1]));
                    case "\"3. To_Currency Code" -> values.put("toCurrency", extractValueFromBrackets(split[i+1]));
                    case "\"8. Bid Price" -> values.put("bid", extractValueFromBrackets(split[i+1].trim()));
                    case "\"9. Ask Price" -> values.put("ask", extractValueFromBrackets(split[i+1].trim()));
                }
            }
            return values;
        }

        private String extractValueFromBrackets(String data) {
            var matcher = PATTERN.matcher(data);
            if (matcher.find()) {
                return matcher.group(0).substring(1, matcher.group(0).length()-1);
            } else {
                return "";
            }
        }
    }

    /**
     * This is a model of our backend.
     */
    private record CurrencyData(
            String time,
            String fromCurrency,
            String toCurrency,
            String bid,
            String ask
    ){
        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{\"time\":\"")
                    .append(time)
                    .append("\", \"fromCurrency\":\"")
                    .append(fromCurrency)
                    .append("\", \"toCurrency\":\"")
                    .append(toCurrency)
                    .append("\", \"bid\":\"")
                    .append(bid)
                    .append("\", \"ask\":\"")
                    .append(ask)
                    .append("\"}")
                    .toString();
        }
    }

    /**
     * This is a simple logger.
     */
    private static void log(String message) {
        var log = new StringBuilder()
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append(" [")
                .append(Thread.currentThread().getName())
                .append("] ")
                .append(message)
                .toString();
        System.out.println(log);
    }
}
