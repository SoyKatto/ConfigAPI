package me.katto.configApi.API;

import com.google.gson.*;
import me.katto.configApi.API.Anotations.ConfigCategory;
import me.katto.configApi.API.Anotations.ConfigField;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class ConfigAPI {
    private final File file;
    protected JsonObject mainJson;
    private long lastModified;

    public ConfigAPI(String path) {
        this.file = new File(path);
        this.mainJson = new JsonObject();
    }

    public void init() {
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
                saveDefaults();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        load();
        startWatcher();
    }

    public void save() {
        try (FileWriter writer = new FileWriter(file)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            writer.write(gson.toJson(mainJson));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void load() {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }
            mainJson = JsonParser.parseString(jsonBuilder.toString()).getAsJsonObject();
            lastModified = file.lastModified();
            updateFieldsFromJson();
            onReload();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveDefaults() {
        updateJsonFromFields();
        save();
    }

    private void updateFieldsFromJson() {
        for (Field field : this.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(ConfigField.class)) continue;
            field.setAccessible(true);
            try {
                String fieldName = field.getName();
                JsonObject parent = getParentObject(field);
                String jsonKey = getString(field, fieldName);

                if (!parent.has(jsonKey)) {
                    Object defaultValue = field.get(this);
                    addJsonValue(parent, jsonKey, defaultValue);
                }

                JsonElement value = parent.get(jsonKey);

                if (field.getType() == String.class) {
                    field.set(this, value.getAsString());

                } else if (field.getType() == int.class) {
                    field.setInt(this, value.getAsInt());

                } else if (field.getType() == long.class) {
                    field.setLong(this, value.getAsLong());

                } else if (field.getType() == boolean.class) {
                    field.setBoolean(this, value.getAsBoolean());

                } else if (List.class.isAssignableFrom(field.getType())) {
                    field.set(this, getListObjects(field, value));

                } else if (Map.class.isAssignableFrom(field.getType())) {
                    field.set(this, getMapObjects(field, value));
                }

            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private static String getString(Field field, String fieldName) {
        String jsonKey = fieldName;
        ConfigCategory categoryAnno = field.getAnnotation(ConfigCategory.class);
        if (categoryAnno != null) {
            String categoryName = categoryAnno.value();
            if (fieldName.startsWith(categoryName)) {
                jsonKey = fieldName.substring(categoryName.length());
                if (jsonKey.startsWith("_") || jsonKey.startsWith("-")) jsonKey = jsonKey.substring(1);
            }
        }
        return jsonKey;
    }

    private static List<Object> getListObjects(Field field, JsonElement value) {
        Type genericType = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
        List<Object> list = new ArrayList<>();
        if (value.isJsonArray()) {
            JsonArray arr = value.getAsJsonArray();
            for (JsonElement elem : arr) {
                list.add(parsePrimitive(elem, genericType));
            }
        }
        return list;
    }

    private static Map<Object, Object> getMapObjects(Field field, JsonElement value) {
        Map<Object, Object> map = new LinkedHashMap<>();

        if (!value.isJsonObject()) return map;

        JsonObject obj = value.getAsJsonObject();

        ParameterizedType pType = (ParameterizedType) field.getGenericType();
        Type keyType = pType.getActualTypeArguments()[0];
        Type valueType = pType.getActualTypeArguments()[1];

        for (String keyStr : obj.keySet()) {
            JsonElement val = obj.get(keyStr);

            Object keyParsed = parsePrimitive(JsonParser.parseString("\"" + keyStr + "\""), keyType);
            Object valueParsed = parsePrimitive(val, valueType);

            map.put(keyParsed, valueParsed);
        }
        return map;
    }

    private static Object parsePrimitive(JsonElement elem, Type type) {
        if (type == String.class) return elem.getAsString();
        if (type == Integer.class || type == int.class) return elem.getAsInt();
        if (type == Long.class || type == long.class) return elem.getAsLong();
        if (type == Boolean.class || type == boolean.class) return elem.getAsBoolean();
        return null;
    }

    private void updateJsonFromFields() {
        for (Field field : this.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(ConfigField.class)) continue;
            field.setAccessible(true);

            try {
                String fieldName = field.getName();
                Object value = field.get(this);

                ConfigCategory categoryAnno = field.getAnnotation(ConfigCategory.class);
                JsonObject parent;
                String jsonKey = fieldName;

                if (categoryAnno != null) {
                    String categoryName = categoryAnno.value();
                    if (!mainJson.has(categoryName)) {
                        mainJson.add(categoryName, new JsonObject());
                    }
                    parent = mainJson.getAsJsonObject(categoryName);

                    if (fieldName.startsWith(categoryName)) {
                        jsonKey = fieldName.substring(categoryName.length());
                        if (jsonKey.startsWith("_") || jsonKey.startsWith("-")) jsonKey = jsonKey.substring(1);
                    }
                } else {
                    parent = mainJson;
                }

                addJsonValue(parent, jsonKey, value);

            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private JsonObject getParentObject(Field field) {
        ConfigCategory categoryAnno = field.getAnnotation(ConfigCategory.class);
        if (categoryAnno != null) {
            String categoryName = categoryAnno.value();
            if (!mainJson.has(categoryName)) {
                mainJson.add(categoryName, new JsonObject());
            }
            return mainJson.getAsJsonObject(categoryName);
        }
        return mainJson;
    }

    private void addJsonValue(JsonObject parent, String key, Object value) {
        if (value == null) {
            parent.add(key, JsonNull.INSTANCE);
            return;
        }

        if (value instanceof String s) {
            parent.addProperty(key, s);
            return;
        }
        if (value instanceof Integer i) {
            parent.addProperty(key, i);
            return;
        }
        if (value instanceof Long l) {
            parent.addProperty(key, l);
            return;
        }
        if (value instanceof Boolean b) {
            parent.addProperty(key, b);
            return;
        }

        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            for (Object item : list) array.add(toJsonPrimitive(item));
            parent.add(key, array);
            return;
        }

        if (value instanceof Map<?, ?> map) {
            JsonObject obj = new JsonObject();
            for (Object k : map.keySet()) {
                String kStr = String.valueOf(k);
                obj.add(kStr, toJsonPrimitive(map.get(k)));
            }
            parent.add(key, obj);
            return;
        }

        parent.add(key, JsonNull.INSTANCE);
    }

    private JsonElement toJsonPrimitive(Object val) {
        if (val instanceof String s) return new JsonPrimitive(s);
        if (val instanceof Integer i) return new JsonPrimitive(i);
        if (val instanceof Long l) return new JsonPrimitive(l);
        if (val instanceof Boolean b) return new JsonPrimitive(b);
        return JsonNull.INSTANCE;
    }

    private void startWatcher() {
        Thread watcherThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    if (file.exists() && file.lastModified() != lastModified) {
                        load();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                }
            }
        });
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    protected void onReload() {}

    public void set(String fieldName, Object value) {
        try {
            Field field = this.getClass().getDeclaredField(fieldName);
            if (!field.isAnnotationPresent(ConfigField.class)) return;
            field.setAccessible(true);
            field.set(this, value);

            ConfigCategory categoryAnno = field.getAnnotation(ConfigCategory.class);
            JsonObject parent;
            String jsonKey = fieldName;

            if (categoryAnno != null) {
                String categoryName = categoryAnno.value();
                if (!mainJson.has(categoryName)) mainJson.add(categoryName, new JsonObject());
                parent = mainJson.getAsJsonObject(categoryName);

                if (fieldName.startsWith(categoryName)) {
                    jsonKey = fieldName.substring(categoryName.length());
                    if (jsonKey.startsWith("_") || jsonKey.startsWith("-")) jsonKey = jsonKey.substring(1);
                }
            } else {
                parent = mainJson;
            }

            addJsonValue(parent, jsonKey, value);

            save();
            onReload();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    public Object get(String fieldName) {
        try {
            Field field = this.getClass().getDeclaredField(fieldName);
            if (!field.isAnnotationPresent(ConfigField.class)) return null;
            field.setAccessible(true);
            return field.get(this);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

    public List<String> getElements() {
        return getElements(null);
    }

    public List<String> getElements(Class<?> type) {
        List<String> elements = new ArrayList<>();

        for (Field field : this.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(ConfigField.class)) continue;
            field.setAccessible(true);

            if (type == null || type.isAssignableFrom(field.getType())) {
                elements.add(field.getName());
            }
        }

        return elements;
    }
}