package com.example.client.transport.restclient;


import com.example.client._types.*;
import com.example.client.json.*;
import com.example.client.json.jackson.*;
import com.example.client.transport.*;
import com.example.client.util.*;
import com.example.ll_restclient.*;
import jakarta.json.stream.*;
import org.apache.http.*;
import org.apache.http.entity.*;
import org.apache.http.impl.nio.client.*;
import org.apache.http.message.*;
import org.apache.http.util.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;




// Test class implementing a subclass of the Transport interface
// prepares an object of type RequestT
@SuppressWarnings({"unused", "RedundantThrows", "UnnecessaryLocalVariable"})
public class YelpRestTransport implements YelpFusionTransport {
    static final ContentType JsonContentType;

    static CloseableHttpAsyncClient client;

    static {

        if (Version.VERSION == null) {
            JsonContentType = ContentType.APPLICATION_JSON;
        } else {

            JsonContentType = ContentType.create(  //Creates a new instance of ContentType with the given parameters.
                    "application/vnd.elasticsearch+json",    //mimeType – MIME type. It may not be null or empty. It may not contain characters <">, <;>, <,> reserved by the HTTP specification. params – parameters.
                    new BasicNameValuePair("compatible-with",  // A name-value pair parameter used as an element of HTTP messages.
                            String.valueOf(Version.VERSION.major())) // Returns: content type
            );
        }
    }


    @Override
    public void close() throws IOException {

    }


    private static class RequestFuture<T> extends CompletableFuture<T> {
        private volatile Cancellable cancellable;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && cancellable != null) {
                cancellable.cancel();
            }
            return cancelled;
        }
    }

    private final RestClient restClient;
    private final JsonpMapper mapper;
    private final YelpRestTransportOptions transportOptions;

    public YelpRestTransport(RestClient restClient, JsonpMapper mapper, TransportOptions options) throws IOException { // TransportOptions
        this.restClient = restClient;
        this.mapper = mapper;
        this.transportOptions = options == null ?
                YelpRestTransportOptions.initialOptions() : YelpRestTransportOptions.of(options);
    }

    public YelpRestTransport(RestClient restClient, JsonpMapper mapper) throws IOException {
        this(restClient, mapper, null);
    }


    public RestClient restClient() {
        return restClient;
    }

    
    public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
            RequestT request,
            Endpoint<RequestT, ResponseT, ErrorT> endpoint,
            TransportOptions transportOptions) throws IOException, URISyntaxException {

        Request clientRequest = prepareLowLevelRequest(request, endpoint, transportOptions);
        
        Response clientResponse = restClient.performRequest(clientRequest);

        if(clientResponse.getStatusLine().getStatusCode() != 200) {
            clientResponse = executeRequestUsingCurl(clientRequest);
        }

        return getHighLevelResponse(clientResponse, endpoint);
    }


    // TODO: use Apache HttpComponents: HttpClient, HttpResponse and  return ResponseT
    public Response executeRequestUsingCurl(Request request) throws IOException {
        RequestOptions options = request.getOptions();

        StringBuilder sb = new StringBuilder("curl ");
        String currentHeader = null;

        if (options != null) {
            for (Header header : options.getHeaders())
                if (header.getName().contains("Authorization")) {
                    currentHeader = String.format("-H \"%s: %s\" ",header.getName(),header.getValue());
                }
        }
        sb.append(currentHeader); //curl -H Authorization: Bearer $YELP_API_KEY

        HttpHost httpHost = restClient.getNodes().get(0).getHost();
        String hostName = httpHost.getHostName();
        String schemeName = httpHost.getSchemeName();

        sb.append(schemeName)
                .append("://")
                .append(hostName); //curl -H Authorization: Bearer $YELP_API_KEY https://api.yelp.com

        String method = request.getMethod();

        String path = request.getEndpoint();
        sb.append(path); //curl -H Authorization: Bearer $YELP_API_KEY https://api.yelp.com/v3/<fusion endpoint>

        RequestOptions requestOptions = request.getOptions();

        Map<String, String> params = request.getParameters();

        String delim = "?";
        for (Map.Entry<String, String> param : params.entrySet()) {
            sb.append(delim);
            delim = "&";
            String key = param.getKey();
            String value = param.getValue();

            if (value.startsWith("[")) {
                String formatted = value.replaceAll("[^a-zA-Z0-9]", "");
            }
            sb.append(key).append("=").append(value);
        }

        Process process = Runtime.getRuntime().exec(sb.toString());

        InputStream inputStream = process.getInputStream();

        String response = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        Response clientResponse = new JacksonJsonpMapper().objectMapper().readValue(response, Response.class);

        RequestLine requestLine = clientResponse.getRequestLine();
        HttpHost host = clientResponse.getHost();
        int statusCode = clientResponse.getStatusLine().getStatusCode();
        Header[] headers = clientResponse.getHeaders();
        String responseBody = EntityUtils.toString(clientResponse.getEntity());

        return clientResponse;
    }


    public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
            RequestT request,
            Endpoint<RequestT, ResponseT, ErrorT> endpoint,
            TransportOptions options
    ) {
        
        Request clientReq = prepareLowLevelRequest(request, endpoint, options); //
        try {
            Response response = restClient.performRequest(clientReq);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        RequestFuture<ResponseT> future = new RequestFuture<>();

    
        boolean disableRequiredChecks = ApiTypeHelper.requiredPropertiesCheckDisabled();
        
        future.cancellable = restClient.performRequestAsync(clientReq, new ResponseListener() {
            @Override
            public void onSuccess(Response clientResp) {
                try (ApiTypeHelper.DisabledChecksHandle h =
                             ApiTypeHelper.DANGEROUS_disableRequiredPropertiesCheck(disableRequiredChecks)) {

                    ResponseT response = getHighLevelResponse(clientResp, endpoint);
                    future.complete(response);

                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }

            @Override
            public void onFailure(Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private <RequestT, ResponseT, ErrorT> Request prepareLowLevelRequest(
            RequestT request, 
            Endpoint<RequestT,ResponseT,ErrorT> endpoint, 
            TransportOptions options) {
        String method = endpoint.method(request);
        String path = endpoint.requestUrl(request);
        Map<String, String> params = endpoint.queryParameters(request);

        Request clientReq = new Request(method, path);

        RequestOptions restOptions = options == null ?
                transportOptions.restClientRequestOptions() :
                RestClientOptions.of(options).restClientRequestOptions();

        if (restOptions != null) {
            clientReq.setOptions(restOptions);
        }

        clientReq.addParameters(params);

        if (endpoint.hasRequestBody()) {
            // Request has a body and must implement JsonpSerializable or NdJsonpSerializable
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            if (request instanceof NdJsonpSerializable) {
                writeNdJson((NdJsonpSerializable) request, baos);
            } else {
                JsonGenerator generator = mapper.jsonProvider().createGenerator(baos);
                mapper.serialize(request, generator);
                generator.close();
            }

            clientReq.setEntity(new ByteArrayEntity(baos.toByteArray(), JsonContentType));
        }
        // Request parameter intercepted by LLRC
        clientReq.addParameter("ignore", "400,401,403,404,405");
        return clientReq;
        
    }

    private <ResponseT, ErrorT> ResponseT getHighLevelResponse(
            Response clientResp,
            Endpoint<?, ResponseT, ?> endpoint
    ) throws IOException {
        ResponseT response;
        try {

            int statusCode = clientResp.getStatusLine().getStatusCode();

            HttpEntity entity = clientResp.getEntity();
            if (entity == null) {
                throw new TransportException(
                        "Expecting a response body, but none was sent",
                        endpoint.id(), new com.example.client._types.ResponseException(clientResp)
                );
            }

            JsonEndpoint<?, ResponseT, ?> jsonEndpoint =
                    (JsonEndpoint<?, ResponseT, ?>) endpoint;

            com.example.client.transport.JsonEndpoint<?, ResponseT, ?> jsonEndpoint_ =
                    (com.example.client.transport.JsonEndpoint<?, ResponseT, ?>) endpoint; // parameter endpoint

            entity = new BufferedHttpEntity(entity);

            InputStream content = entity.getContent(); // Returns a content stream of the entity.

            JsonpDeserializer<ResponseT> responseParser = jsonEndpoint.responseDeserializer(); // The entity parser for the response body.

            try (JsonParser parser = mapper.jsonProvider().createParser(content)) { // use JSON-P provider to create json parser. parse the stream of the entity. Repeatable entities are expected to create a new instance of
                response = responseParser.deserialize(parser, mapper); // Deserialize a value. The value starts at the next state in the JSON stream.
            }

        } catch (IOException | UnsupportedOperationException e) {
            throw new RuntimeException(e);
        }
        EntityUtils.consume(clientResp.getEntity()); // Ensures that the entity content is fully consumed and the content stream, if exists, is closed.

        return response;
    }

    private void writeNdJson(NdJsonpSerializable value, ByteArrayOutputStream baos) {
        Iterator<?> values = value._serializables();
        while (values.hasNext()) {
            Object item = values.next();
            if (item instanceof NdJsonpSerializable && item != value) { // do not recurse on the item itself
                writeNdJson((NdJsonpSerializable) item, baos);
            } else {
                JsonGenerator generator = mapper.jsonProvider().createGenerator(baos);
                mapper.serialize(item, generator);
                generator.close();
                baos.write('\n');
            }
        }
    }

    @Override
    public JsonpMapper jsonpMapper() {
        return this.mapper;
    }

    @Override
    public TransportOptions options() {
        return transportOptions;
    }

}
