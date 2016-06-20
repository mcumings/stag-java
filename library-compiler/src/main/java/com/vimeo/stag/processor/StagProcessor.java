package com.vimeo.stag.processor;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import com.vimeo.stag.GsonAdapterKey;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * The MIT License (MIT)
 * <p/>
 * Copyright (c) 2016 Vimeo
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
@SupportedAnnotationTypes("com.vimeo.stag.GsonAdapterKey")
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public final class StagProcessor extends AbstractProcessor {

    private static final String PACKAGE_NAME = "com.vimeo.stag.generated";
    private static final String PARSE_UTILS = "ParseUtils";
    private static final String TYPE_ADAPTERS = "AdapterFactory";
    private static final String ADAPTER = "Adapter";

    private static final boolean DEBUG = true;

    private boolean mHasBeenProcessed = false;

    private Set<String> mSupportedTypes = new HashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (mHasBeenProcessed) {
            return true;
        }
        log("Beginning @GsonAdapterKey annotation processing");
        mHasBeenProcessed = true;
        Map<TypeMirror, List<VariableElement>> variableMap = new HashMap<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(GsonAdapterKey.class)) {
            if (element instanceof VariableElement) {
                final VariableElement variableElement = (VariableElement) element;

                Set<Modifier> modifiers = variableElement.getModifiers();
                if (modifiers.contains(Modifier.FINAL)) {
                    throw new RuntimeException("Unable to access field \"" +
                                               variableElement.getSimpleName().toString() + "\" in class " +
                                               variableElement.getEnclosingElement().asType() +
                                               ", field must not be final.");
                } else if (!modifiers.contains(Modifier.PUBLIC)) {
                    throw new RuntimeException("Unable to access field \"" +
                                               variableElement.getSimpleName().toString() + "\" in class " +
                                               variableElement.getEnclosingElement().asType() +
                                               ", field must public.");
                }
                mSupportedTypes.add(variableElement.getEnclosingElement().asType().toString());
                addToListMap(variableMap, variableElement.getEnclosingElement().asType(), variableElement);
            }
        }

        try {
            generateParsingCode(variableMap);
            generateTypeAdapters(variableMap.keySet());
        } catch (IOException e) {
            logError("Error while processing annotations");
            e.printStackTrace();
            return true;
        }
        log("Successfully processed @GsonAdapterKey annotations");
        return true;
    }

    private void generateParsingCode(Map<TypeMirror, List<VariableElement>> map) throws IOException {
        TypeSpec.Builder typeSpecBuilder = TypeSpec.classBuilder(PARSE_UTILS).addModifiers(Modifier.FINAL);
        for (Map.Entry<TypeMirror, List<VariableElement>> entry : map.entrySet()) {
            generateParseAndWriteMethods(typeSpecBuilder, entry.getKey(), entry.getValue());
        }

        JavaFile javaFile = JavaFile.builder(PACKAGE_NAME, typeSpecBuilder.build()).build();

        writeTo(javaFile, processingEnv.getFiler());
    }

    private void generateTypeAdapters(Set<TypeMirror> types) throws IOException {
        TypeSpec.Builder adaptersBuilder =
                TypeSpec.classBuilder(TYPE_ADAPTERS).addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        FieldSpec fieldSpec =
                FieldSpec.builder(Map.class, "sTypeAdapterMap", Modifier.PRIVATE, Modifier.STATIC,
                                  Modifier.FINAL).initializer("new java.util.HashMap();").build();

        adaptersBuilder.addField(fieldSpec);

        MethodSpec registerMethod = MethodSpec.methodBuilder("registerTypeAdapter")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(void.class)
                .addParameter(Class.class, "className")
                .addParameter(TypeAdapter.class, "adapter")
                .addCode("sTypeAdapterMap.put(className.getName(), adapter);")
                .build();

        MethodSpec readAdapterMethod = MethodSpec.methodBuilder("readFromAdapter")
                .addModifiers(Modifier.STATIC)
                .returns(Object.class)
                .addParameter(String.class, "clazz")
                .addParameter(JsonReader.class, "in")
                .addCode("try {\n" +
                         "\treturn ((com.google.gson.TypeAdapter) sTypeAdapterMap.get(clazz)).read(in);\n" +
                         "} catch (IOException e) {\n" +
                         "\te.printStackTrace();\n" +
                         "}\n" +
                         "return null;")
                .build();

        MethodSpec writeAdapterMethod = MethodSpec.methodBuilder("writeToAdapter")
                .addModifiers(Modifier.STATIC)
                .returns(void.class)
                .addParameter(String.class, "clazz")
                .addParameter(JsonWriter.class, "out")
                .addParameter(Object.class, "value")
                .addCode("try {\n" +
                         "\t((com.google.gson.TypeAdapter) sTypeAdapterMap.get(clazz)).write(out, value);\n" +
                         "} catch (IOException e) {\n" +
                         "\te.printStackTrace();\n" +
                         '}')
                .build();

        adaptersBuilder.addMethod(registerMethod);
        adaptersBuilder.addMethod(readAdapterMethod);
        adaptersBuilder.addMethod(writeAdapterMethod);

        for (TypeMirror type : types) {
            String clazz = type.toString();

            String packageName = clazz.substring(0, clazz.lastIndexOf('.'));
            String clazzName = clazz.substring(packageName.length() + 1, clazz.length());

            TypeSpec.Builder innerAdapterBuilder = TypeSpec.classBuilder(clazzName + ADAPTER)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .superclass(TypeAdapter.class);

            MethodSpec writeMethod = MethodSpec.methodBuilder("write")
                    .addParameter(JsonWriter.class, "out")
                    .addParameter(Object.class, "value")
                    .returns(void.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .addException(IOException.class)
                    .addCode(
                            "ParseUtils.write(out, (" + ClassName.get(packageName, clazzName) + ") value);\n")
                    .build();

            MethodSpec readMethod = MethodSpec.methodBuilder("read")
                    .addParameter(JsonReader.class, "in")
                    .returns(Object.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .addException(IOException.class)
                    .addCode("return ParseUtils.parse" + clazzName + "(in);\n")
                    .build();

            innerAdapterBuilder.addMethod(writeMethod);
            innerAdapterBuilder.addMethod(readMethod);

            adaptersBuilder.addType(innerAdapterBuilder.build());
        }

        JavaFile javaFile = JavaFile.builder(PACKAGE_NAME, adaptersBuilder.build()).build();

        writeTo(javaFile, processingEnv.getFiler());
    }

    private void generateParseAndWriteMethods(TypeSpec.Builder typeSpecBuilder, TypeMirror type,
                                              List<VariableElement> elements) {

        MethodSpec writeSpec = generateWriteSpec(type, elements);

        MethodSpec parseSpec = generateParseSpec(type, elements);

        typeSpecBuilder.addMethod(writeSpec);
        typeSpecBuilder.addMethod(parseSpec);

    }

    private MethodSpec generateWriteSpec(TypeMirror type, List<VariableElement> elements) {
        String clazz = type.toString();

        String packageName = clazz.substring(0, clazz.lastIndexOf('.'));
        String clazzName = clazz.substring(packageName.length() + 1, clazz.length());

        MethodSpec.Builder writeBuilder = MethodSpec.methodBuilder("write")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(JsonWriter.class, "writer")
                .addParameter(ClassName.get(packageName, clazzName), "object")
                .addException(IOException.class)
                .returns(void.class)
                .addCode("\twriter.beginObject();\n" +
                         "\tif (object == null) {\n" +
                         "\t\treturn;\n" +
                         "\t} else {\n");

        for (VariableElement element : elements) {
            String name = getJsonName(element);
            String variableName = element.getSimpleName().toString();
            String variableType = element.asType().toString();

            boolean isPrimitive = isPrimitive(variableType);

            if (!isPrimitive) {
                writeBuilder.addCode("\t\tif (object." + variableName + " != null) {\n");
            }
            writeBuilder.addCode("\t\t\twriter.name(\"" + name + "\");\n");
            writeBuilder.addCode("\t\t\t" + getWriteType(variableType, variableName) + '\n');
            if (!isPrimitive) {
                writeBuilder.addCode("\t\t}\n");
            }
        }
        writeBuilder.addCode("\t}\n" + "\twriter.endObject();\n");

        return writeBuilder.build();
    }

    private static String getJsonName(VariableElement element) {
        String name = element.getAnnotation(GsonAdapterKey.class).value();

        if (name == null || name.isEmpty()) {
            name = element.getSimpleName().toString();
        }
        return name;
    }

    private MethodSpec generateParseSpec(TypeMirror type, List<VariableElement> elements) {
        String clazz = type.toString();

        String packageName = clazz.substring(0, clazz.lastIndexOf('.'));
        String clazzName = clazz.substring(packageName.length() + 1, clazz.length());

        MethodSpec.Builder parseBuilder = MethodSpec.methodBuilder("parse" + clazzName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.get(packageName, clazzName))
                .addParameter(JsonReader.class, "reader")
                .addException(IOException.class)
                .addCode("\treader.beginObject();\n" +
                         '\n' +
                         '\t' + clazz + " object = new " + clazz + "();\n" +
                         "\twhile (reader.hasNext()) {\n" +
                         "\t\tString name = reader.nextName();\n" +
                         "\t\tcom.google.gson.stream.JsonToken jsonToken = reader.peek();\n" +
                         "\t\tif (jsonToken == com.google.gson.stream.JsonToken.NULL) {\n" +
                         "\t\t\treader.skipValue();\n" +
                         "\t\t\tcontinue;\n" +
                         "\t\t}\n" +
//                        "java.lang.System.out.println(jsonToken.toString());" +
                         "\t\tswitch (name) {\n");

        for (VariableElement element : elements) {
            String name = getJsonName(element);

            String variableName = element.getSimpleName().toString();

            String variableType = element.asType().toString();

            parseBuilder.addCode("\t\t\tcase \"" + name + "\":\n" +
                                 "\t\t\t\tobject." + variableName + " = " + getReadType(variableType) + '\n' +
                                 "\t\t\t\tbreak;\n");
        }

        parseBuilder.addCode("\t\t\tdefault:\n" +
                             "\t\t\t\treader.skipValue();\n" +
                             "\t\t\t\tbreak;\n" +
                             "\t\t}\n" +
                             "\t}\n" +
                             '\n' +
                             "\treader.endObject();\n" +
                             "\treturn object;\n");

        return parseBuilder.build();
    }

    private String getReadType(String type) {
        if (type.equals(long.class.getName())) {
            return "reader.nextLong();";
        } else if (type.equals(double.class.getName())) {
            return "reader.nextDouble();";
        } else if (type.equals(boolean.class.getName())) {
            return "reader.nextBoolean();";
        } else if (type.equals(String.class.getName())) {
            return "reader.nextString();";
        } else if (type.equals(int.class.getName())) {
            return "reader.nextInt();";
        } else {
            if (!mSupportedTypes.contains(type)) {
                return '(' + type + ")AdapterFactory.readFromAdapter(\"" + type + "\", reader);";
            } else {
                String packageName = type.substring(0, type.lastIndexOf('.'));
                String clazzName = type.substring(packageName.length() + 1, type.length());
                return "ParseUtils.parse" + clazzName + "(reader);";
            }
        }
    }

    private String getWriteType(String type, String variableName) {
        if (type.equals(long.class.getName()) ||
            type.equals(double.class.getName()) ||
            type.equals(boolean.class.getName()) ||
            type.equals(String.class.getName()) ||
            type.equals(int.class.getName())) {
            return "writer.value(object." + variableName + ");";
        } else {
            log("Supported type: " + mSupportedTypes.contains(type));
            if (!mSupportedTypes.contains(type)) {
                return "AdapterFactory.writeToAdapter(\"" + type + "\", writer, object);";
            } else {
                return "ParseUtils.write(writer, object." + variableName + ");";
            }
        }
    }

    private static boolean isPrimitive(String type) {
        return type.equals(long.class.getName()) ||
               type.equals(double.class.getName()) ||
               type.equals(boolean.class.getName()) ||
               type.equals(int.class.getName());
    }

    private static void addToListMap(Map<TypeMirror, List<VariableElement>> map, TypeMirror key,
                                     VariableElement value) {
        if (key == null || value == null) {
            return;
        }
        List<VariableElement> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
        }
        list.add(value);
        map.put(key, list);
    }

    private static void log(CharSequence message) {
        if (DEBUG) {
            System.out.println(message);
        }
    }

    private void logError(CharSequence message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message);
    }

    private static void writeTo(JavaFile file, Filer filer) throws IOException {
        String fileName =
                file.packageName.isEmpty() ? file.typeSpec.name : file.packageName + '.' + file.typeSpec.name;
        List<Element> originatingElements = file.typeSpec.originatingElements;
        JavaFileObject filerSourceFile = filer.createSourceFile(fileName, originatingElements.toArray(
                new Element[originatingElements.size()]));
        filerSourceFile.delete();
        Writer writer = null;
        try {
            writer = filerSourceFile.openWriter();
            file.writeTo(writer);
        } catch (Exception e) {
            try {
                filerSourceFile.delete();
            } catch (Exception ignored) {
            }
            throw e;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {

                }
            }
        }
    }
}
