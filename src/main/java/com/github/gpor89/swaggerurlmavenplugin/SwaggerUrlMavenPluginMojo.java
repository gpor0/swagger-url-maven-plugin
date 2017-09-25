package com.github.gpor89.swaggerurlmavenplugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mojo(name = "generateurls")
public class SwaggerUrlMavenPluginMojo extends AbstractMojo {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Pattern PARAM_PATTERN = Pattern.compile("\\{(.*?)\\}");

    /**
     * The greeting to display.
     */
    @Parameter(property = "generateurls.swaggerJsonSpecFile", defaultValue = "/target/swagger.json")
    private File swaggerSpec;

    @Parameter(property = "generateurls.outputFile", defaultValue = "/target/requests.txt")
    private File outputFile;

    @Parameter(property = "generateurls.overrideHost", defaultValue = "{myHost}")
    private String host;

    public SwaggerUrlMavenPluginMojo() {
    }

    protected SwaggerUrlMavenPluginMojo(final File file) {
        this.swaggerSpec = file;
    }

    //thanks to João Silva@stackoverflow
    public static Set<Set<String>> powerSet(Set<String> originalSet) {
        Set<Set<String>> sets = new HashSet<Set<String>>();
        if (originalSet.isEmpty()) {
            sets.add(new HashSet<String>());
            return sets;
        }
        List<String> list = new ArrayList<String>(originalSet);
        String head = list.get(0);
        Set<String> rest = new HashSet<String>(list.subList(1, list.size()));
        for (Set<String> set : powerSet(rest)) {
            Set<String> newSet = new HashSet<String>();
            newSet.add(head);
            newSet.addAll(set);
            sets.add(newSet);
            sets.add(set);
        }
        return sets;
    }

    public void execute() throws MojoExecutionException {

        if (swaggerSpec == null || !swaggerSpec.exists() || !swaggerSpec.isFile()) {
            getLog().error("Could not find swagger spec file " + swaggerSpec);
            return;
        }

        try {
            final JsonNode swaggerSpecNode = MAPPER.readTree(swaggerSpec);

            //todo check spec version
            final String swaggerVer = swaggerSpecNode.get("swagger").asText();
            if (!"2.0".equals(swaggerVer)) {
                getLog().warn("Unknown swagger spec version " + swaggerVer);
            }

            final List<Api> apiList = new LinkedList<Api>();

            List<String> globalSchemes = parseArray(swaggerSpecNode, "schemes");
            List<String> globalProduces = parseArray(swaggerSpecNode, "produces");
            String globalHost = host != null ? host : (swaggerSpecNode.get("host") == null ? "" : swaggerSpecNode.get("host").asText());
            String globalBasePath = swaggerSpecNode.get("basePath") == null ? "" : swaggerSpecNode.get("basePath").asText();

            if ("/".equalsIgnoreCase(globalBasePath)) {
                globalBasePath = "";
            }

            Iterator<Map.Entry<String, JsonNode>> paths = swaggerSpecNode.get("paths").fields();
            while (paths.hasNext()) {
                Map.Entry<String, JsonNode> pathEn = paths.next();
                final String apiPath = pathEn.getKey();

                final JsonNode methods = pathEn.getValue();
                Iterator<Map.Entry<String, JsonNode>> entries = methods.fields();
                while (entries.hasNext()) {

                    Map.Entry<String, JsonNode> en = entries.next();
                    final Api api = new Api(globalSchemes, globalProduces, globalHost, globalBasePath, apiPath, en.getKey());

                    JsonNode details = en.getValue();

                    ArrayNode params = (ArrayNode) details.get("parameters");
                    api.setParameters(parseApiParameters(params));

                    ArrayNode producesArray = (ArrayNode) details.get("produces");
                    if (producesArray != null) {
                        //override global produce
                        Set<String> produceSet = new TreeSet<String>();
                        for (Iterator<JsonNode> it = producesArray.elements(); it.hasNext(); ) {
                            String produces = it.next().asText();
                            produceSet.add(produces);
                        }
                        api.overrideGlobalProduceSetWith(produceSet);
                    }

                    apiList.add(api);
                }
            }

            Set<String> apiSet = new TreeSet<String>();
            for (Api api : apiList) {
                apiSet.addAll(api.getUrlOptions());
            }

            for (String api : apiSet) {
                getLog().info(api);
            }

            getLog().info("Requests generated");
        } catch (IOException e) {

        }

    }

    private Set<ApiParameter> parseApiParameters(ArrayNode params) {

        Set<ApiParameter> result = new HashSet<ApiParameter>();

        if (params == null) {
            return result;
        }

        final Iterator<JsonNode> elements = params.elements();
        while (elements.hasNext()) {
            final JsonNode paramNode = elements.next();
            final String paramName = paramNode.get("name").asText();
            final ApiParameter.ParamType type = ApiParameter.parseParamType(paramNode.get("in").asText());
            final boolean required = paramNode.get("required").asBoolean();
            final ArrayNode enumVals = (ArrayNode) paramNode.get("enum");

            List<String> enumValList = new LinkedList<String>();
            if (enumVals != null) {
                for (Iterator<JsonNode> i = enumVals.elements(); i.hasNext(); ) {
                    String val = i.next().asText();
                    enumValList.add(val);
                }
            }

            result.add(new ApiParameter(paramName, type, required, enumValList));
        }

        return result;
    }

    private final List<String> parseArray(final JsonNode swaggerSpecNode, final String elName) {

        final List<String> result = new LinkedList<String>();

        JsonNode e = swaggerSpecNode.get(elName);
        if (e == null) {
            return result;
        }

        final Iterator<JsonNode> schemesIt = e.elements();

        while (schemesIt.hasNext()) {
            final JsonNode schema = schemesIt.next();
            if (schema != null && schema.isTextual()) {
                result.add(schema.asText());
            }
        }

        return result;
    }

    public static class ApiParameter {

        private String paramName;
        private ParamType paramType;
        private boolean required;
        private List<String> values = new LinkedList<String>();

        public ApiParameter(String paramName, ParamType type, boolean required, List<String> enumValList) {
            this.paramName = paramName;
            this.paramType = type;
            this.required = required;

            if (enumValList != null) {
                this.values = enumValList;
            }
        }

        public static ParamType parseParamType(String paramType) {
            if ("path".equalsIgnoreCase(paramType)) {
                return ParamType.PATH;
            }
            if ("query".equalsIgnoreCase(paramType)) {
                return ParamType.QUERY;
            }

            //todo implement other...
            return null;
        }

        public enum ParamType {
            PATH, QUERY;
        }

    }

    public class Api {

        //contains scheme host and global base paths
        private Set<String> baseUrlSet = new TreeSet<String>();
        //contains produce media types
        private Set<String> produceSet = new TreeSet<String>();
        //contains path params
        private String apiPath;
        //contains method (POST,PUT,GET...)
        private String httpMethod;

        //path param map
        private Map<String, ApiParameter> pathParameterMap = new HashMap<String, ApiParameter>();
        //query param map
        private Map<String, ApiParameter> queryParameterMap = new HashMap<String, ApiParameter>();


        public Api(List<String> globalSchemes, List<String> globalProduces, String globalHost, String globalBasePath, String apiPath, String
                httpMethod) {

            for (String scheme : globalSchemes) {
                baseUrlSet.add(scheme + "://" + globalHost + globalBasePath);
            }

            if (globalProduces != null) {
                this.produceSet = new TreeSet<String>(globalProduces);
            }
            this.apiPath = apiPath;
            this.httpMethod = httpMethod;
        }

        public void overrideGlobalProduceSetWith(Set<String> produceSet) {
            this.produceSet = produceSet;
        }

        public void setParameters(Set<ApiParameter> apiParameters) {
            for (ApiParameter apiParameter : apiParameters) {
                if (apiParameter.paramType == ApiParameter.ParamType.PATH) {
                    pathParameterMap.put(apiParameter.paramName, apiParameter);
                } else if (apiParameter.paramType == ApiParameter.ParamType.QUERY) {
                    queryParameterMap.put(apiParameter.paramName, apiParameter);
                }
            }
        }

        public Set<String> getUrlOptions() {

            Set<String> methodCallSet = new TreeSet<String>();

            //initial set are urls with required query params
            StringBuilder sb = new StringBuilder();
            for (ApiParameter parameter : queryParameterMap.values()) {
                if (parameter.required) {
                    sb.append(parameter.paramName + "={" + parameter.paramName + "}&");
                }
            }
            if (sb.length() > 0) {
                sb.delete(sb.length() - 1, sb.length());
            }

            String queryParam = sb.length() > 0 ? "?" + sb.toString() : "";
            for (String baseUrl : baseUrlSet) {
                methodCallSet.add(httpMethod.toUpperCase() + " " + baseUrl + apiPath + queryParam);
            }

            //enrich with produce
            Set<String> tmpSet = new HashSet<String>();
            for (String produce : produceSet) {
                for (String url : methodCallSet) {
                    tmpSet.add(produce + " " + url);
                }
            }
            methodCallSet = new TreeSet<String>(tmpSet);


            //now we multiply set power of optional query params
            Set<String> optionalQueryParams = new TreeSet<String>();
            for (ApiParameter apiParameter : queryParameterMap.values()) {
                if (!apiParameter.required) {
                    optionalQueryParams.add(apiParameter.paramName);
                }
            }

            final Set<Set<String>> powerSetResult = powerSet(optionalQueryParams);

            Set<String> methodCallSetTmp = new HashSet<String>();
            for (Set<String> qpPermutation : powerSetResult) {
                StringBuilder qsb = new StringBuilder();
                for (String paramName : qpPermutation) {
                    qsb.append(paramName + "={" + paramName + "}&");
                }
                if (qsb.length() > 0) {
                    qsb.delete(qsb.length() - 1, qsb.length());

                    for (String method : methodCallSet) {
                        methodCallSetTmp.add(method + "?" + qsb.toString());
                    }
                }
            }

            //at this point we have all urls with required params and optional query param permutation
            methodCallSet.addAll(methodCallSetTmp);

            //try to find enum set by param and multiply...
            methodCallSetTmp = new TreeSet<String>(methodCallSet);
            for (String url : methodCallSetTmp) {
                Matcher matcher = PARAM_PATTERN.matcher(url);
                while (matcher.find()) {
                    String paramName = matcher.group(1);
                    ApiParameter apiParameter;
                    if (queryParameterMap.containsKey(paramName)) {
                        apiParameter = queryParameterMap.get(paramName);
                    } else if (pathParameterMap.containsKey(paramName)) {
                        apiParameter = pathParameterMap.get(paramName);
                    } else {
                        continue;
                    }

                    if (!apiParameter.values.isEmpty()) {
                        //remove entry with placeholders, we will insert entries with enum values
                        methodCallSet.remove(url);
                    }

                    for (String enumValue : apiParameter.values) {
                        String urlWithEnumValue = url.replaceAll("\\{" + paramName + "\\}", enumValue);
                        methodCallSet.add(urlWithEnumValue);
                    }
                }
            }

            return methodCallSet;
        }
    }

}