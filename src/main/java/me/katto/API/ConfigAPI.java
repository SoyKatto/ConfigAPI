package me.katto.API;

import com.google.gson.*;
import me.katto.API.Anotations.ConfigCategory;
import me.katto.API.Anotations.ConfigField;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

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

                if (!parent.has(fieldName)) {
                    Object defaultValue = field.get(this);
                    addJsonValue(parent, fieldName, defaultValue);
                }

                JsonElement value = parent.get(fieldName);
                if (field.getType() == String.class) {
                    field.set(this, value.getAsString());
                } else if (field.getType() == int.class) {
                    field.setInt(this, value.getAsInt());
                } else if (field.getType() == long.class) {
                    field.setLong(this, value.getAsLong());
                } else if (field.getType() == boolean.class) {
                    field.setBoolean(this, value.getAsBoolean());
                } else if (List.class.isAssignableFrom(field.getType())) {
                    List<Object> list = getObjects(field, value);
                    field.set(this, list);
                }

            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private static List<Object> getObjects(Field field, JsonElement value) {
        Type genericType = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
        List<Object> list = new ArrayList<>();
        if (value.isJsonArray()) {
            JsonArray arr = value.getAsJsonArray();
            for (JsonElement elem : arr) {
                if (genericType == String.class) list.add(elem.getAsString());
                else if (genericType == Integer.class) list.add(elem.getAsInt());
                else if (genericType == Long.class) list.add(elem.getAsLong());
                else if (genericType == Boolean.class) list.add(elem.getAsBoolean());
            }
        }
        return list;
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
        switch (value) {
            case String s -> parent.addProperty(key, s);
            case Integer i -> parent.addProperty(key, i);
            case Long l -> parent.addProperty(key, l);
            case Boolean b -> parent.addProperty(key, b);
            case List<?> objects -> {
                JsonArray array = new JsonArray();
                for (Object item : objects) {
                    if (item instanceof String) array.add((String) item);
                    else if (item instanceof Integer) array.add((Integer) item);
                    else if (item instanceof Long) array.add((Long) item);
                    else if (item instanceof Boolean) array.add((Boolean) item);
                }
                parent.add(key, array);
            }
            case null, default -> parent.add(key, JsonNull.INSTANCE);
        }
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
            e.printStackTrace();
        }
        return null;
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