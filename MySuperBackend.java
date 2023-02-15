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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;

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
            var values = (Map<String,Object>) new JsonParser(response.body()).parseToMap().get("Realtime Currency Exchange Rate");
            return new CurrencyData(values.get("6. Last Refreshed").toString(),
                    values.get("1. From_Currency Code").toString(),
                    values.get("3. To_Currency Code").toString(),
                    values.get("8. Bid Price").toString(),
                    values.get("9. Ask Price").toString());
        }
    }

    /**
     * Simple JSON parser.
     */
    private static class JsonParser {
        private int pointer;
        private final String data;

        public JsonParser(String data) {
            this.data = data;
            this.pointer = 0;
        }

        public Map<String, Object> parseToMap() {
            var map = new HashMap<String, Object>();

            parseObject:
            while (pointer < data.length()) {
                switch (data.charAt(pointer)) {
                    case '{', ',' -> {
                        var key = parseString();
                        var value = parseValue();
                        map.put(key, value);
                    }
                    case '}' -> {
                        pointer++;
                        break parseObject;
                    }
                    default -> pointer++;
                }
            }
            return map;
        }

        public List<Object> parseToList() {
            var list = new ArrayList<Object>();

            parseArray:
            while (pointer < data.length()) {
                switch (data.charAt(pointer)) {
                    case '[', ',' -> {
                        pointer++;
                        list.add(parseValue());
                    }
                    case ']' -> {
                        pointer++;
                        break parseArray;
                    }
                    default -> pointer++;
                }
            }
            return list;
        }

        private String parseString() {
            String result = "";
            while (pointer < data.length()) {
                if (data.charAt(pointer) == '"') {
                    pointer++;
                    int start = pointer;
                    while (pointer < data.length() && data.charAt(pointer) != '"') {
                        pointer++;
                    }
                    result = data.substring(start, pointer);
                    pointer++;
                    break;
                } else {
                    pointer++;
                }
            }
            return result;
        }

        public Object parseValue() {
            while (pointer < data.length()) {
                switch (data.charAt(pointer)) {
                    case '{' -> {
                        return parseToMap();
                    }
                    case '[' -> {
                        return parseToList();
                    }
                    case '"' -> {
                        return parseString();
                    }
                    default -> {
                        if (Character.isDigit(data.charAt(pointer))) {
                            return parseNumber();
                        } else {
                            pointer++;
                        }
                    }
                }
            }
            return null;
        }

        private Number parseNumber() {
            int start = pointer;
            while (pointer < data.length() && Character.isDigit(data.charAt(pointer))) {
                pointer++;
            }
            return Integer.parseInt(data.substring(start, pointer));
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
