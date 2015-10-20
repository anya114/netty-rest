package org.rakam.server.http;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import io.swagger.jackson.ModelResolver;
import io.swagger.models.ArrayModel;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.RefModel;
import io.swagger.models.Response;
import io.swagger.models.Scheme;
import io.swagger.models.SecurityRequirement;
import io.swagger.models.Swagger;
import io.swagger.models.Tag;
import io.swagger.models.parameters.AbstractSerializableParameter;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.FormParameter;
import io.swagger.models.parameters.HeaderParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.parameters.PathParameter;
import io.swagger.models.parameters.QueryParameter;
import io.swagger.models.parameters.SerializableParameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.PropertyBuilder;
import io.swagger.models.properties.RefProperty;
import io.swagger.util.PrimitiveType;
import org.rakam.server.http.annotations.Api;
import org.rakam.server.http.annotations.ApiImplicitParam;
import org.rakam.server.http.annotations.ApiImplicitParams;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.ApiResponse;
import org.rakam.server.http.annotations.ApiResponses;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.server.http.annotations.ParamBody;
import org.rakam.server.http.annotations.ResponseHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Produces;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class SwaggerReader {
    private final Logger LOGGER = LoggerFactory.getLogger(SwaggerReader.class);

    private final Swagger swagger;
    private final ModelConverters modelConverters;

    public SwaggerReader(Swagger swagger, ObjectMapper mapper, Map<Class, PrimitiveType> externalTypes) {
        this.swagger = swagger;
        modelConverters = new ModelConverters(mapper);
        modelConverters.addConverter(new ModelResolver(mapper));
        if (externalTypes != null) {
            setExternalTypes(externalTypes);
        }
    }

    public SwaggerReader(Swagger swagger, ObjectMapper mapper) {
        this(swagger, mapper, null);
    }

    private void setExternalTypes(Map<Class, PrimitiveType> externalTypes) {
        // ugly hack until swagger supports adding external classes as primitive types
        try {
            Field externalTypesField = PrimitiveType.class.getDeclaredField("EXTERNAL_CLASSES");
            Field modifiersField = Field.class.getDeclaredField("modifiers");

            modifiersField.setAccessible(true);
            externalTypesField.setAccessible(true);
            modifiersField.set(externalTypesField, externalTypesField.getModifiers() & ~Modifier.FINAL);

            Map<String, PrimitiveType> externalTypesInternal = externalTypes.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().getName(), e -> e.getValue()));
            externalTypesField.set(null, externalTypesInternal);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.warn("Couldn't set external types", e);
        }
    }

    public Swagger read(Class cls) {
        return read(cls, "", false, ImmutableSet.of(), new ArrayList<>());
    }

    protected Swagger read(Class<?> cls, String parentPath, boolean readHidden, Set<String> parentTags, List<Parameter> parentParameters) {
        Api api = cls.getAnnotation(Api.class);
        javax.ws.rs.Path apiPath = cls.getAnnotation(javax.ws.rs.Path.class);

        // only read if allowing hidden apis OR api is not marked as hidden
        if ((api != null && readHidden) || (api != null && !api.hidden())) {
            // the value will be used as a tag for 2.0 UNLESS a Tags annotation is present
            Set<String> tags = extractTags(api);

            Authorization[] authorizations = api.authorizations();

            List<SecurityRequirement> securities = new ArrayList<>();
            for (Authorization auth : authorizations) {
                if (auth.value() != null && !"".equals(auth.value())) {
                    SecurityRequirement security = new SecurityRequirement();
                    security.requirement(auth.value());
                    security.setName(auth.value());
                    securities.add(security);
                }
            }

            // parse the method
            Method methods[] = cls.getMethods();
            for (Method method : methods) {
                ApiOperation apiOperation = method.getAnnotation(ApiOperation.class);
                javax.ws.rs.Path methodPath = method.getAnnotation(javax.ws.rs.Path.class);

                String operationPath = getPath(apiPath, methodPath, parentPath);
                if (operationPath != null && apiOperation != null) {
                    String[] pps = operationPath.split("/");
                    String[] pathParts = new String[pps.length];
                    Map<String, String> regexMap = new HashMap<>();

                    for (int i = 0; i < pps.length; i++) {
                        String p = pps[i];
                        if (p.startsWith("{")) {
                            int pos = p.indexOf(":");
                            if (pos > 0) {
                                String left = p.substring(1, pos);
                                String right = p.substring(pos + 1, p.length() - 1);
                                pathParts[i] = "{" + left + "}";
                                regexMap.put(left, right);
                            } else
                                pathParts[i] = p;
                        } else pathParts[i] = p;
                    }
                    StringBuilder pathBuilder = new StringBuilder();
                    for (String p : pathParts) {
                        if (!p.isEmpty())
                            pathBuilder.append("/").append(p);
                    }
                    operationPath = pathBuilder.toString();

                    String httpMethod = extractOperationMethod(apiOperation, method);

                    Operation operation = parseMethod(method);

                    if (operation == null) {
                        continue;
                    }

                    if (parentParameters != null) {
                        parentParameters.forEach(operation::parameter);
                    }
                    operation.getParameters().stream()
                            .filter(param -> regexMap.get(param.getName()) != null)
                            .forEach(param -> {
                                String pattern = regexMap.get(param.getName());
                                param.setPattern(pattern);
                            });

                    String protocols = apiOperation.protocols();
                    if (!"".equals(protocols)) {
                        String[] parts = protocols.split(",");
                        for (String part : parts) {
                            String trimmed = part.trim();
                            if (!"".equals(trimmed))
                                operation.scheme(Scheme.forValue(trimmed));
                        }
                    }

                    if (isSubResource(method)) {
                        Class<?> responseClass = method.getReturnType();
                        read(responseClass, operationPath, true, tags, operation.getParameters());
                    }

                    // can't continue without a valid http method
                    if (httpMethod != null) {
                        ApiOperation op = method.getAnnotation(ApiOperation.class);
                        if (op != null) {
                            for (String tag : op.tags()) {
                                if (!"".equals(tag)) {
                                    operation.tag(tag);
                                    swagger.tag(new Tag().name(tag));
                                }
                            }
                        }

                        tags.forEach(operation::tag);

                        if (operation != null) {
                            Consumes consumes = method.getAnnotation(Consumes.class);
                            if (consumes != null) {
                                operation.consumes(Arrays.asList(consumes.value()));
                            } else {
                                operation.consumes("application/json");
                            }

                            Produces produces = method.getAnnotation(Produces.class);
                            if (produces != null) {
                                operation.produces(Arrays.asList(produces.value()));
                            } else {
                                operation.produces("application/json");
                            }

                            securities.forEach(operation::security);

                            Path path = swagger.getPath(operationPath);
                            if (path == null) {
                                path = new Path();
                                swagger.path(operationPath, path);
                            }
                            path.set(httpMethod, operation);
                        }
                    }

                }
            }
        }
        return swagger;
    }

    protected boolean isSubResource(Method method) {
        Class<?> responseClass = method.getReturnType();
        if (responseClass != null && responseClass.getAnnotation(Api.class) != null) {
            return true;
        }
        return false;
    }

    private void readImplicitParameters(Method method, Operation operation) {
        ApiImplicitParams implicitParams = method.getAnnotation(ApiImplicitParams.class);
        if (implicitParams != null && implicitParams.value().length > 0) {
            if (Arrays.stream(implicitParams.value())
                    .anyMatch(param -> param.dataType().equals("object"))) {
                BodyParameter bodyParameter = new BodyParameter();
                ModelImpl model = new ModelImpl();
                for (ApiImplicitParam param : implicitParams.value()) {
                    Parameter p = readImplicitParam(param);
                    if (!(p instanceof AbstractSerializableParameter)) {
                        throw new IllegalStateException();
                    }
                    AbstractSerializableParameter serializableParameter = (AbstractSerializableParameter) p;
                    Property items = PropertyBuilder.build(serializableParameter.getType(),
                            serializableParameter.getFormat(), ImmutableMap.of());
                    items.setRequired(param.required());
                    items.setAccess(param.access());
                    items.setDefault(param.defaultValue() == null ? null : param.defaultValue());
                    model.addProperty(param.name(), items);
                }
                String name = method.getDeclaringClass().getName() + "." + method.getName();
                model.setName(name);

                // TODO: fix for https://github.com/swagger-api/swagger-codegen/issues/354 remove ref when the issue is fixed.
                swagger.addDefinition(name, model);

                bodyParameter.name(name);
                bodyParameter.setSchema(new RefModel(model.getName()));

                operation.addParameter(bodyParameter);
            } else {
                for (ApiImplicitParam param : implicitParams.value()) {
                    Parameter p = readImplicitParam(param);
                    if (p != null) {
                        operation.addParameter(p);
                    }
                }
            }
        }
    }

    protected Parameter readImplicitParam(ApiImplicitParam param) {
        final AbstractSerializableParameter p;
        if (param.paramType().equalsIgnoreCase("path")) {
            p = new PathParameter();
        } else if (param.paramType().equalsIgnoreCase("query")) {
            p = new QueryParameter();
        } else if (param.paramType().equalsIgnoreCase("form") || param.paramType().equalsIgnoreCase("formData")) {
            p = new FormParameter();
        } else if (param.paramType().equalsIgnoreCase("body")) {
            p = null;
        } else if (param.paramType().equalsIgnoreCase("header")) {
            p = new HeaderParameter();
        } else {
            LOGGER.warn("Unknown implicit parameter type: [" + param.paramType() + "]");
            return null;
        }
        p.setName(param.name());
        p.setType(param.dataType());
        p.setDefaultValue(param.defaultValue());
        p.setAccess(param.access());

        final Type type = typeFromString(param.dataType());
        return ParameterProcessor.applyAnnotations(swagger, p, type == null ? String.class : type,
                Arrays.<Annotation>asList(param));
    }

    public static Type typeFromString(String type) {
        final PrimitiveType primitive = PrimitiveType.fromName(type);
        if (primitive != null) {
            return primitive.getKeyClass();
        }
        try {
            return Class.forName(type);
        } catch (Exception e) {
//            LOGGER.error(String.format("Failed to resolve '%s' into class", type), e);
        }
        return null;
    }

    private static Type getActualReturnType(Method method) {
        if (method.getReturnType().equals(CompletableFuture.class)) {
            Type responseClass = method.getGenericReturnType();
            return ((ParameterizedType) responseClass).getActualTypeArguments()[0];
        }

        return method.getGenericReturnType();
    }

    private static Class getActualReturnClass(Method method) {
        if (method.getReturnType().equals(CompletableFuture.class)) {
            Type responseClass = method.getGenericReturnType();
            Type type = ((ParameterizedType) responseClass).getActualTypeArguments()[0];
            if (type instanceof ParameterizedType) {
                return (Class) ((ParameterizedType) type).getRawType();
            } else {
                return (Class) type;
            }
        }

        return method.getReturnType();
    }

    protected Set<String> extractTags(Api api) {
        Set<String> output = new LinkedHashSet<>();

        boolean hasExplicitTags = false;
        for (String tag : api.tags()) {
            if (!"".equals(tag)) {
                hasExplicitTags = true;
                output.add(tag);
            }
        }
        if (!hasExplicitTags) {
            // derive tag from api path + description
            String tagString = api.value().replace("/", "");
            if (!"".equals(tagString))
                output.add(tagString);
        }
        return output;
    }

    String getPath(javax.ws.rs.Path classLevelPath, javax.ws.rs.Path methodLevelPath, String parentPath) {
        if (classLevelPath == null && methodLevelPath == null)
            return null;
        StringBuilder b = new StringBuilder();
        if (parentPath != null && !"".equals(parentPath) && !"/".equals(parentPath)) {
            if (!parentPath.startsWith("/"))
                parentPath = "/" + parentPath;
            if (parentPath.endsWith("/"))
                parentPath = parentPath.substring(0, parentPath.length() - 1);

            b.append(parentPath);
        }
        if (classLevelPath != null) {
            b.append(classLevelPath.value());
        }
        if (methodLevelPath != null && !"/".equals(methodLevelPath.value())) {
            String methodPath = methodLevelPath.value();
            if (!methodPath.startsWith("/") && !b.toString().endsWith("/")) {
                b.append("/");
            }
            if (methodPath.endsWith("/")) {
                methodPath = methodPath.substring(0, methodPath.length() - 1);
            }
            b.append(methodPath);
        }
        String output = b.toString();
        if (!output.startsWith("/"))
            output = "/" + output;
        if (output.endsWith("/") && output.length() > 1)
            return output.substring(0, output.length() - 1);
        else
            return output;
    }

    public Map<String, Property> parseResponseHeaders(ResponseHeader[] headers) {
        Map<String, Property> responseHeaders = null;
        if (headers != null && headers.length > 0) {
            for (ResponseHeader header : headers) {
                String name = header.name();
                if (!"".equals(name)) {
                    if (responseHeaders == null)
                        responseHeaders = new HashMap<>();
                    String description = header.description();
                    Class<?> cls = header.response();
                    String container = header.responseContainer();

                    if (!cls.equals(java.lang.Void.class) && !"void".equals(cls.toString())) {
                        Property responseProperty;
                        Property property = modelConverters.readAsProperty(cls);
                        if (property != null) {
                            if ("list".equalsIgnoreCase(container))
                                responseProperty = new ArrayProperty(property);
                            else if ("map".equalsIgnoreCase(container))
                                responseProperty = new MapProperty(property);
                            else
                                responseProperty = property;
                            responseProperty.setDescription(description);
                            responseHeaders.put(name, responseProperty);
                        }
                    }
                }
            }
        }
        return responseHeaders;
    }

    public Operation parseMethod(Method method) {
        Operation operation = new Operation();

        ApiOperation apiOperation = method.getAnnotation(ApiOperation.class);
        ApiResponses responseAnnotation = method.getAnnotation(ApiResponses.class);

        String responseContainer = null;

        Type responseClass = null;
        Map<String, Property> defaultResponseHeaders = new HashMap<>();

        if (apiOperation != null) {
            if (apiOperation.hidden())
                return null;

            String nickname = apiOperation.nickname();
            if(nickname == null || nickname.isEmpty()) {
                operation.operationId(method.getName());
            }else {
                operation.operationId(nickname);
            }

            defaultResponseHeaders = parseResponseHeaders(apiOperation.responseHeaders());

            operation
                    .summary(apiOperation.value())
                    .description(apiOperation.notes());

            if (apiOperation.response() != null && !Void.class.equals(apiOperation.response()))
                responseClass = apiOperation.response();
            if (!"".equals(apiOperation.responseContainer()))
                responseContainer = apiOperation.responseContainer();
            if (apiOperation.authorizations() != null) {
                List<SecurityRequirement> securities = new ArrayList<>();
                for (Authorization auth : apiOperation.authorizations()) {
                    if (auth.value() != null && !"".equals(auth.value())) {
                        SecurityRequirement security = new SecurityRequirement();
                        security.requirement(auth.value());
                        security.setName(auth.value());
                        securities.add(security);
                    }
                }
                securities.forEach(operation::security);
            }
        }

        if(method.isAnnotationPresent(JsonRequest.class)) {
            responseClass = getActualReturnType(method);
        }

        if (responseClass != null && !responseClass.equals(java.lang.Void.class)) {
            if(responseClass instanceof Class && TypeToken.class.equals(((Class) responseClass).getSuperclass())) {
                responseClass = ((ParameterizedType) ((Class) responseClass).getGenericSuperclass()).getActualTypeArguments()[0];
            }
            if (isPrimitive(responseClass)) {
                Property responseProperty;
                Property property = modelConverters.readAsProperty(responseClass);
                if (property != null) {
                    if ("list".equalsIgnoreCase(responseContainer))
                        responseProperty = new ArrayProperty(property);
                    else if ("map".equalsIgnoreCase(responseContainer))
                        responseProperty = new MapProperty(property);
                    else
                        responseProperty = property;
                    operation.response(200, new Response()
                            .description("successful operation")
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                }
            } else if (!responseClass.equals(java.lang.Void.class) && !"void".equals(responseClass.toString())) {
                String name = responseClass.getTypeName();
                Model model = modelConverters.read(responseClass).get(name);
                if(model == null) {
                        Property p = modelConverters.readAsProperty(responseClass);
                        operation.response(200, new Response()
                                .description("successful operation")
                                .schema(p)
                                .headers(defaultResponseHeaders));
                } else {
                    model.setReference(responseClass.getTypeName());

                    Property responseProperty;

                    if ("list".equalsIgnoreCase(responseContainer))
                        responseProperty = new ArrayProperty(new RefProperty().asDefault(name));
                    else if ("map".equalsIgnoreCase(responseContainer))
                        responseProperty = new MapProperty(new RefProperty().asDefault(name));
                    else
                        responseProperty = new RefProperty().asDefault(name);
                    operation.response(200, new Response()
                            .description("successful operation")
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                    swagger.model(name, model);
                }

            }

            Map<String, Model> models = modelConverters.readAll(responseClass);
            for (String key : models.keySet()) {
                swagger.model(key, models.get(key));
                swagger.addDefinition(key, models.get(key));
            }
        }

        Annotation annotation;
        annotation = method.getAnnotation(Consumes.class);
        if (annotation != null) {
            String[] apiConsumes = ((Consumes) annotation).value();
            for (String mediaType : apiConsumes)
                operation.consumes(mediaType);
        }

        annotation = method.getAnnotation(Produces.class);
        if (annotation != null) {
            String[] apiProduces = ((Produces) annotation).value();
            for (String mediaType : apiProduces)
                operation.produces(mediaType);
        }

        if (responseAnnotation != null) {
            for (ApiResponse apiResponse : responseAnnotation.value()) {
                Map<String, Property> responseHeaders = parseResponseHeaders(apiResponse.responseHeaders());

                Response response = new Response()
                        .description(apiResponse.message())
                        .headers(responseHeaders);

                if (apiResponse.code() == 0)
                    operation.defaultResponse(response);
                else
                    operation.response(apiResponse.code(), response);

                responseClass = apiResponse.response();
                if (responseClass != null && !responseClass.equals(java.lang.Void.class)) {
                    Map<String, Model> models = modelConverters.read(responseClass);
                    for (String key : models.keySet()) {
                        response.schema(new RefProperty().asDefault(key));
                        swagger.model(key, models.get(key));
                    }
                }
            }
        }
        operation.deprecated(method.isAnnotationPresent(Deprecated.class));

        java.lang.reflect.Parameter[] parameters;
        Type explicitType = null;

        String name, reference;
        String methodIdentifier = method.getDeclaringClass().getName() + "_" + method.getName();
        if (!apiOperation.request().equals(Void.class)) {
            Class<?> clazz = apiOperation.request();
            if(clazz.getSuperclass().equals(TypeToken.class)) {
                explicitType = ((ParameterizedType) clazz.getGenericSuperclass()).getActualTypeArguments()[0];
                parameters = null;
                name = null;
                reference = null;
            } else {
                parameters = readApiBody(clazz);
                name = clazz.getSimpleName();
                reference = clazz.getName();
            }
        } else
        if (method.getParameterCount() == 1 && method.getParameters()[0].getAnnotation(ParamBody.class) != null) {
            Class<?> type = method.getParameters()[0].getType();

            parameters = readApiBody(type);
            name = type.getSimpleName();
            reference = type.getName();
        } else {
            parameters =  method.getParameters();
            name = method.getDeclaringClass().getSimpleName() + "_" + method.getName();
            reference = methodIdentifier;
        }

        if(parameters != null && parameters.length > 0) {
            java.lang.reflect.Parameter firstParameter = parameters[0];
            if (firstParameter.isAnnotationPresent(ApiParam.class)) {

                List<String> params = Arrays.asList("string", "number", "integer", "boolean");

                List<Property> properties = Arrays.stream(parameters).map(parameter ->
                        modelConverters.readAsProperty(parameter.getParameterizedType())).collect(Collectors.toList());

                boolean isSchema = properties.stream().anyMatch(property -> params.indexOf(property.getType()) == -1 &&
                        !((property instanceof ArrayProperty) && params.indexOf(((ArrayProperty) property).getItems().getType()) > -1));

                List<Parameter> list;
                if (!isSchema) {
                    list = readFormParameters(parameters);
                } else {
                    list = readMethodParameters(parameters, properties, name, reference);
                }
                operation.setParameters(list);
            } else if (firstParameter.getType().equals(RakamHttpRequest.class)) {
                readImplicitParameters(method, operation);
            } else {
                throw new IllegalStateException();
            }
        } else
        if(explicitType != null) {
            Property property = modelConverters.readAsProperty(explicitType);
            BodyParameter bodyParameter = new BodyParameter();
            bodyParameter.setName(methodIdentifier);
            bodyParameter.setRequired(true);
            modelConverters.readAll(explicitType).forEach(swagger::addDefinition);

            if (property instanceof ArrayProperty) {
                ArrayModel arrayModel = new ArrayModel();
                Property items = ((ArrayProperty) property).getItems();
                arrayModel.items(items);
                swagger.addDefinition(methodIdentifier, arrayModel);

                RefModel refModel = new RefModel();
                refModel.set$ref(methodIdentifier);
                bodyParameter.setSchema(refModel);
            } else {
                throw new UnsupportedOperationException();
            }

            operation.setParameters(ImmutableList.of(bodyParameter));
        }

        if (operation.getResponses() == null) {
            operation.defaultResponse(new Response().description("successful operation"));
        }
        return operation;
    }

    java.lang.reflect.Parameter[] readApiBody(Class<?> type) {
        List<Constructor<?>> constructors = Arrays.stream(type.getConstructors())
                .filter(c -> c.isAnnotationPresent(JsonCreator.class))
                .collect(Collectors.toList());

        if (constructors.size() > 1) {
            throw new IllegalArgumentException(format("%s has more then one constructor annotation with @ParamBody. There must be only one.",
                    type.getSimpleName()));
        }

        if (constructors.isEmpty()) {
            throw new IllegalArgumentException(format("%s doesn't have any constructor annotation with @JsonCreator.",
                    type.getSimpleName()));
        }

        java.lang.reflect.Parameter[] parameters = constructors.get(0).getParameters();
        if(parameters.length > 0 && !parameters[0].isAnnotationPresent(ApiParam.class)) {
            throw new IllegalArgumentException(format("%s constructor parameters don't have @ApiParam annotation.",
                    type.getSimpleName()));
        }

        Model model = swagger.getDefinitions().get(type.getSimpleName());
        if(model != null && !model.getReference().equals(type.getName())) {
            throw new IllegalStateException();
        }

        return parameters;
    }

    List<Parameter> readMethodParameters(java.lang.reflect.Parameter[] parameters, List<Property> properties, String name, String reference) {
        ModelImpl model = new ModelImpl();
        for (int i = 0; i < properties.size(); i++) {
            Property property = properties.get(i);
            java.lang.reflect.Parameter parameter = parameters[i];
            ApiParam ann = parameter.getAnnotation(ApiParam.class);
            if(ann == null) {
                continue;
            }
            model.addProperty(ann.name(), property);
            if (property instanceof RefProperty) {
                Map<String, Model> subProperty = modelConverters.read(parameter.getParameterizedType());
                String simpleRef = ((RefProperty) property).getSimpleRef();
                swagger.addDefinition(simpleRef, subProperty.get(simpleRef));
            }
            if (property instanceof ArrayProperty) {
                ArrayModel arrayModel = new ArrayModel();
                Property items = ((ArrayProperty) property).getItems();
                arrayModel.items(items);
                if (items instanceof RefProperty) {
                    Type type = ((ParameterizedType) parameter.getParameterizedType()).getActualTypeArguments()[0];
                    // it reads fields of classes but what we actually want to do is to make the class serializable with Jackson library.
                    // therefore, it's a better idea to use constructor that has @JsonCreator annotation.
                    Map<String, Model> read = modelConverters.readAll(type);
                    model.addProperty(property.getName(), null);

                    String refName = ((RefProperty) items).getSimpleRef();
                    for (Map.Entry<String, Model> entry : read.entrySet()) {
                        swagger.addDefinition(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        model.setName(name);
        model.setReference(reference);

        BodyParameter bodyParameter = new BodyParameter();
        bodyParameter.name(name);
        bodyParameter.setSchema(new RefModel(model.getName()));
        bodyParameter.setRequired(true);
        swagger.addDefinition(name, model);

        return Arrays.asList(bodyParameter);
    }

    private List<Parameter> readFormParameters(java.lang.reflect.Parameter[] parameters) {
        return Arrays.stream(parameters).filter(p -> p.isAnnotationPresent(ApiParam.class)).map(parameter -> {
            ApiParam ann = parameter.getAnnotation(ApiParam.class);
            Property property = modelConverters.readAsProperty(parameter.getParameterizedType());

            FormParameter formParameter = new FormParameter();

            formParameter.setRequired(property.getRequired());
            formParameter.setName(ann.name());
            formParameter.setDescription(property.getDescription());
            formParameter.setDefaultValue(property.getExample());

            formParameter.setType(property.getType());
            formParameter.setFormat(property.getFormat());
            if (property instanceof ArrayProperty) {
                formParameter.setItems(((ArrayProperty) property).getItems());
            }
            return formParameter;
        }).collect(Collectors.toList());
    }

    private List<Parameter> getParameters(Class<?> cls, Type type, Annotation[] annotations) {
        // look for path, query
        boolean isArray = isMethodArgumentAnArray(cls, type);
        List<Parameter> parameters;

        LOGGER.debug("getParameters for " + cls);
        Set<Class<?>> classesToSkip = new HashSet<>();
        parameters = extractParameters(annotations, type);

        if (parameters.size() > 0) {
            for (Parameter parameter : parameters) {
                applyAnnotations(swagger, parameter, cls, annotations, isArray);
            }
        } else {
            LOGGER.debug("no parameter found, looking at body params");
            if (classesToSkip.contains(cls) == false) {
                if (type instanceof ParameterizedType) {
                    ParameterizedType ti = (ParameterizedType) type;
                    Type innerType = ti.getActualTypeArguments()[0];
                    if (innerType instanceof Class) {
                        Parameter param = applyAnnotations(swagger, null, (Class) innerType, annotations, isArray);
                        if (param != null) {
                            parameters.add(param);
                        }
                    }
                } else {
                    Parameter param = applyAnnotations(swagger, null, cls, annotations, isArray);
                    if (param != null) {
                        parameters.add(param);
                    }
                }
            }
        }
        return parameters;
    }

    public List<Parameter> extractParameters(Annotation[] annotations, Type type) {
        String defaultValue = null;

        List<Parameter> parameters = new ArrayList<>();
        Parameter parameter = null;

        for (Annotation annotation : annotations) {
            if (annotation instanceof ApiParam) {
                FormParameter qp = new FormParameter()
                        .name(((ApiParam) annotation).name());
                qp.setDefaultValue(defaultValue);
                Property schema = modelConverters.readAsProperty(type);
                if (schema != null) {
                    qp.setProperty(schema);
                    if (schema instanceof ArrayProperty) {
                        qp.setItems(((ArrayProperty) schema).getItems());
                    }
                }
                parameter = qp;
            }
        }
        if (parameter != null) {
            parameters.add(parameter);
        }

        return parameters;
    }

    public String extractOperationMethod(ApiOperation apiOperation, Method method) {
        if (apiOperation.httpMethod() != null && !"".equals(apiOperation.httpMethod()))
            return apiOperation.httpMethod().toLowerCase();
        else if (method.getAnnotation(javax.ws.rs.GET.class) != null)
            return "get";
        else if (method.getAnnotation(javax.ws.rs.PUT.class) != null)
            return "put";
        else if (method.getAnnotation(javax.ws.rs.POST.class) != null)
            return "post";
        else if (method.getAnnotation(javax.ws.rs.DELETE.class) != null)
            return "delete";
        else if (method.getAnnotation(javax.ws.rs.OPTIONS.class) != null)
            return "options";
        else if (method.getAnnotation(javax.ws.rs.HEAD.class) != null)
            return "patch";
        else if (method.getAnnotation(HttpMethod.class) != null) {
            HttpMethod httpMethod = method.getAnnotation(HttpMethod.class);
            return httpMethod.value().toLowerCase();
        }
        return "post";
    }

    boolean isPrimitive(Type cls) {
        boolean out = false;

        Property property = modelConverters.readAsProperty(cls);
        if (property == null)
            out = false;
        else if ("integer".equals(property.getType()))
            out = true;
        else if ("string".equals(property.getType()))
            out = true;
        else if ("number".equals(property.getType()))
            out = true;
        else if ("boolean".equals(property.getType()))
            out = true;
        else if ("array".equals(property.getType()))
            out = true;
        else if ("file".equals(property.getType()))
            out = true;
        return out;
    }

    public Parameter applyAnnotations(Swagger swagger, Parameter parameter, Class<?> cls, Annotation[] annotations, boolean isArray) {
        boolean shouldIgnore = false;
        String allowableValues;

        Optional<Annotation> any = Arrays.stream(annotations).filter(ann -> ann instanceof ApiParam).findAny();
        if (any.isPresent()) {
            ApiParam param = (ApiParam) any.get();

            if (parameter != null) {
                parameter.setRequired(param.required());
                if (param.name() != null && !"".equals(param.name()))
                    parameter.setName(param.name());
                parameter.setDescription(param.value());
                parameter.setAccess(param.access());
                allowableValues = param.allowableValues();
                if (allowableValues != null) {
                    if (allowableValues.startsWith("range")) {
                        // TODO handle range
                    } else {
                        String[] values = allowableValues.split(",");
                        List<String> _enum = new ArrayList<>();
                        for (String value : values) {
                            String trimmed = value.trim();
                            if (!trimmed.equals(""))
                                _enum.add(trimmed);
                        }
                        if (parameter instanceof SerializableParameter) {
                            SerializableParameter p = (SerializableParameter) parameter;
                            if (_enum.size() > 0)
                                p.setEnum(_enum);
                        }
                    }
                }
            } else if (shouldIgnore == false) {
                // must be a body param
                BodyParameter bp = new BodyParameter();
                if (param.name() != null && !"".equals(param.name()))
                    bp.setName(param.name());
                else
                    bp.setName("body");
                bp.setDescription(param.value());

                if (cls.isArray() || isArray) {
                    Class<?> innerType;
                    if (isArray) {// array has already been detected
                        innerType = cls;
                    } else
                        innerType = cls.getComponentType();
                    LOGGER.debug("inner type: " + innerType + " from " + cls);
                    Property innerProperty = modelConverters.readAsProperty(innerType);
                    if (innerProperty == null) {
                        Map<String, Model> models = modelConverters.read(innerType);
                        if (models.size() > 0) {
                            for (String name : models.keySet()) {
                                if (name.indexOf("java.util") == -1) {
                                    bp.setSchema(
                                            new ArrayModel().items(new RefProperty().asDefault(name)));
                                    if (swagger != null)
                                        swagger.addDefinition(name, models.get(name));
                                }
                            }
                        }
                        models = modelConverters.readAll(innerType);
                        if (swagger != null) {
                            for (String key : models.keySet()) {
                                swagger.model(key, models.get(key));
                            }
                        }
                    } else {
                        LOGGER.debug("found inner property " + innerProperty);
                        bp.setSchema(new ArrayModel().items(innerProperty));

                        // creation of ref property doesn't add model to definitions - do it now instead
                        if (innerProperty instanceof RefProperty && swagger != null) {
                            Map<String, Model> models = modelConverters.read(innerType);
                            String name = ((RefProperty) innerProperty).getSimpleRef();
                            swagger.addDefinition(name, models.get(name));

                            LOGGER.debug("added model definition for RefProperty " + name);
                        }
                    }

                } else {
                    Map<String, Model> models = modelConverters.read(cls);
                    if (models.size() > 0) {
                        for (String name : models.keySet()) {
                            if (name.indexOf("java.util") == -1) {
                                if (isArray)
                                    bp.setSchema(new ArrayModel().items(new RefProperty().asDefault(name)));
                                else
                                    bp.setSchema(new RefModel().asDefault(name));
                                if (swagger != null)
                                    swagger.addDefinition(name, models.get(name));
                            }
                        }
                        models = modelConverters.readAll(cls);
                        if (swagger != null) {
                            for (String key : models.keySet()) {
                                swagger.model(key, models.get(key));
                            }
                        }
                    } else {
                        Property prop = modelConverters.readAsProperty(cls);
                        if (prop != null) {
                            ModelImpl model = new ModelImpl();
                            model.setType(prop.getType());
                            bp.setSchema(model);
                        }
                    }
                }
                parameter = bp;
            }
        }
        return parameter;
    }


    public static boolean isMethodArgumentAnArray(final Class<?> paramClass, final Type paramGenericType) {
        final Class<?>[] interfaces = paramClass.getInterfaces();
        boolean isArray = false;

        for (final Class<?> aCls : interfaces) {
            if (List.class.equals(aCls)) {
                isArray = true;

                break;
            }
        }

        if (paramGenericType instanceof ParameterizedType) {
            final Type[] parameterArgTypes = ((ParameterizedType) paramGenericType).getActualTypeArguments();
            Class<?> testClass = paramClass;

            for (Type parameterArgType : parameterArgTypes) {
                if (testClass.isAssignableFrom(List.class)) {
                    isArray = true;

                    break;
                }

                testClass = (Class<?>) parameterArgType;
            }
        }

        return isArray;
    }
}