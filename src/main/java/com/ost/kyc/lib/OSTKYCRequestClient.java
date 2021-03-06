package com.ost.kyc.lib;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;
import okhttp3.*;
import okio.Buffer;
import okio.ByteString;

import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class OSTKYCRequestClient {
    private String apiKey;
    private String apiSecret;
    private String apiEndpoint;
    private Long timeout;
    private static final Gson gson = new Gson();
    private OkHttpClient client;
    private static final Escaper FormParameterEscaper = UrlEscapers.urlFormParameterEscaper();
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static Boolean DEBUG = ("true").equalsIgnoreCase( System.getenv("OST_KYC_SDK_DEBUG") );
    private static Boolean VERBOSE = false;

    static class HttpParam {
        private String paramName;
        private String paramValue;

        public HttpParam() {

        }

        public HttpParam(String paramName, String paramValue) {
            this.paramName = paramName;
            this.paramValue = paramValue;
        }

        public String getParamValue() {
            return paramValue;
        }

        public void setParamValue(String paramValue) {
            this.paramValue = paramValue;
        }

        public String getParamName() {
            return paramName;
        }

        public void setParamName(String paramName) {
            this.paramName = paramName;
        }

    }

    public OSTKYCRequestClient(Map<String, Object> params) {
        Object apiKey = params.get("apiKey");
        Object apiSecret = params.get("apiSecret");
        Object apiEndpoint = params.get("apiEndpoint");

        //default timeout is 10 seconds for socket connection
        long timeout = (long) 10;
        if(params.containsKey("config"))
        {
            HashMap<String, Object> config = (HashMap<String, Object>) params.get("config");

            if(config.containsKey("timeout")){
                timeout = (Long) config.get("timeout");
            }
        }

        if (!(apiKey instanceof String)) {
            throw new IllegalArgumentException("Api key not present.");
        }

        if (!(apiSecret instanceof String)) {
            throw new IllegalArgumentException("Api secret not present.");
        }

        if (!(apiEndpoint instanceof String)) {
            throw new IllegalArgumentException("Api EndPoint not present.");
        }

        this.apiKey = (String) apiKey;
        this.apiSecret = (String) apiSecret;
        this.apiEndpoint = (String) apiEndpoint;
        this.timeout = timeout;


        //To-Do: Discuss Dispatcher config with Team.
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(500);
        dispatcher.setMaxRequestsPerHost(150);

        client = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(10, 2, TimeUnit.MINUTES))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .dispatcher(dispatcher)
                .retryOnConnectionFailure(false)
                .build();

    }

    private static String GET_REQUEST = "GET";
    private static String POST_REQUEST = "POST";
    private static String SocketTimeoutExceptionString = "{'success':'false','err':{'code':'GATEWAY_TIMEOUT','internal_id':'TIMEOUT_ERROR','msg':'','error_data':[]}}";


    public JsonObject get(String resource, Map<String, Object> queryParams) throws IOException {
        return send(GET_REQUEST, resource, queryParams);
    }

    public JsonObject post(String resource, Map<String, Object> queryParams) throws IOException {
        return send(POST_REQUEST, resource, queryParams);
    }

    private JsonObject send(String requestType, String resource, Map<String, Object> mapParams) throws IOException {
        // Basic Sanity.
        if (!requestType.equalsIgnoreCase(POST_REQUEST) && !requestType.equalsIgnoreCase(GET_REQUEST)) {
            throw new IOException("Invalid requestType");
        }
        if (null == mapParams) {
            mapParams = new HashMap<String, Object>();
        }

        // Start Building the request, url of request and request form body.
        Request.Builder requestBuilder = new Request.Builder();
        HttpUrl baseUrl = HttpUrl.parse(apiEndpoint + resource);
        HttpUrl.Builder urlBuilder = baseUrl.newBuilder(resource);

        FormBody.Builder formBodyBuilder = new FormBody.Builder();
        if (null == urlBuilder) {
            throw new IOException("Failed to instanciate HttpUrl.Builder. resource or Api Endpoint is incorrect.");
        }

        // Evaluate the url generated so far.
        HttpUrl url = urlBuilder.build();

        // Start Building HMAC Input Buffer by parsing the url.
        Buffer hmacInputBuffer = new Buffer();
//        for (String path : url.pathSegments()) {
//            if (DEBUG && VERBOSE) System.out.println("path:" + path);
//            hmacInputBuffer.writeByte('/').writeUtf8(PathSegmentEscaper.escape(path));
//        }

        hmacInputBuffer.writeUtf8(resource);
        hmacInputBuffer.writeByte('?');

        //Reset urlBuilder.
        urlBuilder = baseUrl.newBuilder();

        mapParams.put("api_key", apiKey);
        mapParams.put("request_timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        ArrayList<HttpParam> params = new ArrayList<HttpParam>();
        String paramKey;
        String paramVal;

        params = buildNestedQuery(params, "", mapParams);

        // Add params to url/form-body & hmacInputBuffer.
        Iterator it = params.iterator();
        boolean firstParam = true;
        while (it.hasNext()) {
            HttpParam pair = (HttpParam) it.next();

            paramKey = pair.getParamName();
            paramVal = pair.getParamValue();

            paramKey = specialCharacterEscape(paramKey);
            paramVal = specialCharacterEscape(paramVal);

            if (!firstParam) {
                hmacInputBuffer.writeByte('&');
            }
            firstParam = false;

            hmacInputBuffer.writeUtf8(paramKey);
            hmacInputBuffer.writeByte('=');
            hmacInputBuffer.writeUtf8(paramVal);
            if (DEBUG) System.out.println("paramKey " + paramKey + " paramVal " + paramVal);

            if (GET_REQUEST.equalsIgnoreCase(requestType)) {
                urlBuilder.addEncodedQueryParameter(paramKey, paramVal);
            } else {
                formBodyBuilder.addEncoded(paramKey, paramVal);
            }
        }

        // Add signature to Params.
        paramKey = "signature";
        paramVal = signQueryParams(hmacInputBuffer);
        if (GET_REQUEST.equalsIgnoreCase(requestType)) {
            urlBuilder.addEncodedQueryParameter(paramKey, paramVal);
        } else {
            formBodyBuilder.addEncoded(paramKey, paramVal);
        }

        // Build the url.
        url = urlBuilder.build();
        if (DEBUG) System.out.println("url = " + url.toString());

        // Set url in requestBuilder.
        requestBuilder.url(url);

        // Build the request Object.
        Request request;
        if (GET_REQUEST.equalsIgnoreCase(requestType)) {
            requestBuilder.get().addHeader("content-type", "x-www-form-urlencoded");
        } else {
            FormBody formBody = formBodyBuilder.build();
            if (DEBUG && VERBOSE) {
                for (int i = 0; i < formBody.size(); i++) {
                    System.out.println(formBody.name(i) + "\t\t" + formBody.value(i));
                }
            }

            requestBuilder.post(formBody);
        }
        request = requestBuilder.build();

        // Make the call and execute.
        String responseBody;
        Call call = client.newCall(request);
        try {
            okhttp3.Response response = call.execute();
            responseBody = getResponseBodyAsString(response);
        }catch (SocketTimeoutException e)
        {
            System.out.println("SocketTimeoutException occured");
            responseBody =  SocketTimeoutExceptionString;
        }
        return buildApiResponse(responseBody);
    }


    private String signQueryParams(Buffer hmacInputBuffer) {
        // Generate Signature for Params.
        SecretKeySpec keySpec = new SecretKeySpec(apiSecret.getBytes(UTF_8), HMAC_SHA256);
        Mac mac;
        try {
            mac = Mac.getInstance(HMAC_SHA256);
            // Initializing a Cipher
            mac.init(keySpec);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
        byte[] bytes = hmacInputBuffer.readByteArray();
        if (DEBUG) System.out.println("bytes to sign: " + new String(bytes, UTF_8));
        // Encryption of bytes
        byte[] result = mac.doFinal(bytes);

        String signature = ByteString.of(result).hex();
        if (DEBUG) System.out.println("signature: " + signature);
        return signature;
    }

    private static String SOMETHING_WRONG_RESPONSE = "{'success': false, 'err': {'code': 'SOMETHING_WENT_WRONG', 'internal_id': 'SDK(SOMETHING_WENT_WRONG)', 'msg': '', 'error_data':[]}}";

    private static String getResponseBodyAsString(okhttp3.Response response) {
        // Process the response.
        String responseBody;
        if (response.body() != null) {
            try {
                responseBody = response.body().string();
                if (responseBody.length() > 0) {
                    if (DEBUG) System.out.println("responseCode: "+response.code()+"\nresponseBody:\n" + responseBody + "\n");
                    return responseBody;
                }
            } catch (IOException e) {
                // Silently handle the error.
                e.printStackTrace();
            }
        }

        // Response does not have a body. Lets create one.
        switch (response.code()) {
            case 502:
                responseBody = "{'success': false, 'err': {'code': 'BAD_GATEWAY', 'internal_id': 'SDK(BAD_GATEWAY)', 'msg': '', 'error_data':[]}}";
                break;
            case 503:
                responseBody = "{'success': false, 'err': {'code': 'SERVICE_UNAVAILABLE', 'internal_id': 'SDK(SERVICE_UNAVAILABLE)', 'msg': '', 'error_data':[]}}";
                break;
            case 504:
                responseBody = "{'success': false, 'err': {'code': 'GATEWAY_TIMEOUT', 'internal_id': 'SDK(GATEWAY_TIMEOUT)', 'msg': '', 'error_data':[]}}";
                break;
            default:
                responseBody = SOMETHING_WRONG_RESPONSE;
        }

        if (DEBUG) System.out.println("local responseBody:\n" + responseBody + "\n");
        return responseBody;
    }

    private static JsonObject buildApiResponse(String jsonString) {
        try {
            return gson.fromJson(jsonString, JsonObject.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (DEBUG)
            System.out.println("Failed to parse response. local responseBody:\n" + SOMETHING_WRONG_RESPONSE + "\n");
        return gson.fromJson(SOMETHING_WRONG_RESPONSE, JsonObject.class);
    }

    private static ArrayList<HttpParam> buildNestedQuery(ArrayList<HttpParam> params, String paramKeyPrefix, Object paramValObj) {

        if (paramValObj instanceof Map) {

            //            sort map.
            Map<String, Object> sortedMap = new TreeMap<String, Object>((Map<? extends String, ?>) paramValObj);
            for (Object paramPair : sortedMap.entrySet()) {
                Map.Entry pair = (Map.Entry) paramPair;
                String key = (String) pair.getKey();
                Object value = pair.getValue();
                String prefix = "";
                if (paramKeyPrefix.isEmpty()){
                     prefix = key;
                }else{
                     prefix = paramKeyPrefix + "[" + key + "]";
                }

                params = buildNestedQuery(params, prefix, value);
            }

        } else if (paramValObj instanceof Collection) {
            Iterator<Object> iterator = ((Collection) paramValObj).iterator();

            while (iterator.hasNext()) {
                Object value = iterator.next();
                String prefix = paramKeyPrefix + "[]";
                params = buildNestedQuery(params, prefix, value);
            }
        } else {
            if(paramValObj != null){
                params.add(new HttpParam(paramKeyPrefix, paramValObj.toString()));
            }else{
                params.add(new HttpParam(paramKeyPrefix, ""));
            }

        }
        return params;
    }

    private static String specialCharacterEscape(String stringToEscape){
        stringToEscape = FormParameterEscaper.escape(stringToEscape);
        stringToEscape = stringToEscape.replace("*", "%26");
        return stringToEscape;
    }
}