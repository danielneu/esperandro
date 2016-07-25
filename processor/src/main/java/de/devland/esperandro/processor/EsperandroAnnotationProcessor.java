/*
 * Copyright 2013 David Kunzler
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package de.devland.esperandro.processor;

import com.squareup.javapoet.*;
import de.devland.esperandro.CacheActions;
import de.devland.esperandro.SharedPreferenceActions;
import de.devland.esperandro.SharedPreferenceMode;
import de.devland.esperandro.annotations.Cached;
import de.devland.esperandro.annotations.SharedPreferences;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.*;

// TODO errorHandling
@SupportedSourceVersion(SourceVersion.RELEASE_7)
@SupportedAnnotationTypes("de.devland.esperandro.annotations.SharedPreferences")
public class EsperandroAnnotationProcessor extends AbstractProcessor {

    private Warner warner;
    private GetterGenerator getterGenerator;
    private PutterGenerator putterGenerator;
    private Map<TypeMirror, Element> rootElements;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        warner = new Warner(processingEnv);
        rootElements = new HashMap<TypeMirror, Element>();

        preProcessEnvironment(roundEnv);


        for (TypeElement typeElement : annotations) {
            if (typeElement.getQualifiedName().toString().equals(Constants.SHARED_PREFERENCES_ANNOTATION_NAME)) {
                Set<? extends Element> interfaces = roundEnv.getElementsAnnotatedWith(SharedPreferences.class);

                for (Element interfaze : interfaces) {
                    // fix some weird behaviour where getElementsAnnotatedWith returns more elements than expected
                    if (interfaze.getKind() == ElementKind.INTERFACE && interfaze.getAnnotation(SharedPreferences
                            .class) != null) {
                        try {
                            // reinitialize getterGenerator and putter to start fresh for each interface
                            getterGenerator = new GetterGenerator(warner);
                            putterGenerator = new PutterGenerator();
                            Cached cacheAnnotation = interfaze.getAnnotation(Cached.class);
                            boolean caching = cacheAnnotation != null;
                            TypeSpec.Builder type = initImplementation(interfaze, cacheAnnotation);
                            processInterfaceMethods(interfaze, interfaze, type, cacheAnnotation);
                            createGenericActions(type, caching);
                            createGenericClassImplementations(type);
                            finish(interfaze, type);
                            checkPreferenceKeys();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }


        return false;
    }

    private void createGenericClassImplementations(TypeSpec.Builder type) throws IOException {
        for (String preferenceName : getterGenerator.getGenericTypeNames().keySet()) {
            TypeSpec innerGenericType = TypeSpec.classBuilder(preferenceName)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addSuperinterface(Serializable.class)
                    .addField(getterGenerator.getGenericTypeNames().get(preferenceName), "value", Modifier.PUBLIC)
                    .build();

            type.addType(innerGenericType);
        }
    }

    private void preProcessEnvironment(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getRootElements()) {
            rootElements.put(element.asType(), element);
        }
    }

    private void processInterfaceMethods(Element topLevelInterface, Element currentInterface,
                                         TypeSpec.Builder type, Cached cachedAnnotation) throws IOException {
        List<? extends Element> potentialMethods = currentInterface.getEnclosedElements();
        for (Element element : potentialMethods) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) element;
                if (putterGenerator.isPutter(method)) {
                    putterGenerator.createPutterFromModel(method, type, cachedAnnotation);
                } else if (getterGenerator.isGetter(method)) {
                    getterGenerator.createGetterFromModel(method, type, cachedAnnotation != null);
                } else {
                    warner.emitError("No valid getter or setter detected.", method);
                }
            }
        }

        List<? extends TypeMirror> interfaces = ((TypeElement) currentInterface).getInterfaces();
        for (TypeMirror subInterfaceType : interfaces) {
            Element subInterface = rootElements.get(subInterfaceType);
            String subInterfaceTypeName = subInterfaceType.toString();
            if (!subInterfaceTypeName.equals(SharedPreferenceActions.class.getName()) &&
                    !subInterfaceTypeName.equals(CacheActions.class.getName())) {
                if (subInterface != null) {
                    processInterfaceMethods(topLevelInterface, subInterface, type, cachedAnnotation);
                } else {
                    try {
                        Class<?> subInterfaceClass = Class.forName(subInterfaceTypeName);
                        processInterfacesReflection(topLevelInterface, subInterfaceClass, type, cachedAnnotation);
                    } catch (ClassNotFoundException e) {
                        warner.emitError("Could not load Interface '" + subInterfaceTypeName + "' for generation.",
                                topLevelInterface);
                    }
                }
            }
        }
    }

    private void processInterfacesReflection(Element topLevelInterface, Class<?> interfaceClass,
                                             TypeSpec.Builder type, Cached cachedAnnotation) throws IOException {

        for (Method method : interfaceClass.getDeclaredMethods()) {
            if (putterGenerator.isPutter(method)) {
                putterGenerator.createPutterFromReflection(method, topLevelInterface, type, cachedAnnotation);
            } else if (getterGenerator.isGetter(method)) {
                getterGenerator.createGetterFromReflection(method, topLevelInterface, type, cachedAnnotation != null);
            } else {
                warner.emitError("No valid getter or setter detected in class '" + interfaceClass.getName() + "' for " +
                        "method: '" + method.getName() + "'.", topLevelInterface);
            }
        }

        for (Class<?> subInterfaceClass : interfaceClass.getInterfaces()) {
            if (subInterfaceClass.getName() != null && !subInterfaceClass.getName().equals(SharedPreferenceActions
                    .class.getName())) {
                processInterfacesReflection(topLevelInterface, subInterfaceClass, type, cachedAnnotation);
            }
        }
    }

    private void checkPreferenceKeys() {
        for (String key : getterGenerator.getPreferenceKeys().keySet()) {
            if (!putterGenerator.getPreferenceKeys().containsKey(key)) {
                warner.emitWarning("No putter found for getter '" + key + "'", getterGenerator.getPreferenceKeys().get(key));
            }
        }

        for (String key : putterGenerator.getPreferenceKeys().keySet()) {
            if (!getterGenerator.getPreferenceKeys().containsKey(key)) {
                warner.emitWarning("No getterGenerator found for putter '" + key + "'", putterGenerator.getPreferenceKeys().get(key));
            }
        }
    }

    private TypeSpec.Builder initImplementation(Element interfaze, Cached cacheAnnotation) {
        TypeSpec.Builder result;
        SharedPreferences prefAnnotation = interfaze.getAnnotation(SharedPreferences.class);
        String preferencesName = prefAnnotation.name();
        SharedPreferenceMode mode = prefAnnotation.mode();
        if (cacheAnnotation != null && preferencesName.equals("")) {
            warner.emitError("Caching cannot be used on default SharedPreferences", interfaze);
        }

        try {
            QualifiedNameable qualifiedNameable = (QualifiedNameable) interfaze;
            boolean preferenceNamePresent = preferencesName != null && !preferencesName.equals("");
            String[] split = qualifiedNameable.getQualifiedName().toString().split("\\.");
            String typeName = split[split.length - 1] + Constants.IMPLEMENTATION_SUFFIX;
            result = TypeSpec.classBuilder(typeName)
                    .addModifiers(Modifier.PUBLIC)
                    .addSuperinterface(SharedPreferenceActions.class)
                    .addSuperinterface(TypeName.get(interfaze.asType()))
                    .addField(ClassName.get("android.content", "SharedPreferences"), "preferences", Modifier.PRIVATE, Modifier.FINAL);
            if (cacheAnnotation != null) {
                result.addSuperinterface(CacheActions.class);
            }
            MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(ClassName.get("android.content", "Context"), "context");
            if (preferenceNamePresent) {
                constructor.addStatement("this.preferences = context.getSharedPreferences($S, $L)", preferencesName,
                        mode.getSharedPreferenceModeStatement());
            } else {
                constructor.addStatement("this.preferences = $T.getDefaultSharedPreferences(context)", ClassName.get("android.preference", "PreferenceManager"));
            }

            if (cacheAnnotation != null) {
                ClassName cacheClass;
                if (cacheAnnotation.support()) {
                    cacheClass = ClassName.get("android.support.v4.util", "LruCache");
                } else {
                    cacheClass = ClassName.get("android.util", "LruCache");
                }
                ParameterizedTypeName lruCache = ParameterizedTypeName.get(
                        cacheClass,
                        ClassName.get(String.class),
                        ClassName.get(Object.class));
                result.addField(lruCache, "cache", Modifier.PRIVATE, Modifier.FINAL);

                constructor.addStatement("cache = new LruCache<$T, $T>($L)", String.class, Object.class, cacheAnnotation.cacheSize());
            }

            result.addMethod(constructor.build());

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }


    private void createGenericActions(TypeSpec.Builder type, boolean caching) throws IOException {

        MethodSpec.Builder get = MethodSpec.methodBuilder("get")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get("android.content", "SharedPreferences"))
                .addStatement("return preferences");

        MethodSpec.Builder contains = MethodSpec.methodBuilder("contains")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(String.class, "key")
                .addStatement("return preferences.contains(key)");

        MethodSpec.Builder remove = MethodSpec.methodBuilder("remove")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(String.class, "key")
                .addStatement("preferences.edit().remove(key).$L", PreferenceEditorCommitStyle.APPLY.getStatementPart());

        MethodSpec.Builder registerListener = MethodSpec.methodBuilder("registerOnChangeListener")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(ClassName.get("android.content", "SharedPreferences.OnSharedPreferenceChangeListener"), "listener")
                .addStatement("preferences.registerOnSharedPreferenceChangeListener(listener)");

        MethodSpec.Builder unregisterListener = MethodSpec.methodBuilder("unregisterOnChangeListener")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(ClassName.get("android.content", "SharedPreferences.OnSharedPreferenceChangeListener"), "listener")
                .addStatement("preferences.unregisterOnSharedPreferenceChangeListener(listener)");

        MethodSpec.Builder clear = MethodSpec.methodBuilder("clear")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addStatement("preferences.edit().clear().$L", PreferenceEditorCommitStyle.APPLY.getStatementPart());

        MethodSpec.Builder clearDefinedBuilder = MethodSpec.methodBuilder("clearDefined")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addStatement("SharedPreferences.Editor editor = preferences.edit()");

        MethodSpec.Builder resetCache = MethodSpec.methodBuilder("resetCache")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addStatement("cache.evictAll()");


        Set<String> preferenceNames = new LinkedHashSet<String>();
        preferenceNames.addAll(putterGenerator.getPreferenceKeys().keySet());
        preferenceNames.addAll(getterGenerator.getPreferenceKeys().keySet());
        for (String preferenceName : preferenceNames) {
            clearDefinedBuilder.addStatement("editor.remove($S)", preferenceName);
        }

        if (caching) {
            remove.addStatement("cache.remove(key)");
            clear.addStatement("cache.evictAll()");
            for (String preferenceName : preferenceNames) {
                clearDefinedBuilder.addStatement("cache.remove($S)", preferenceName);
            }
        }

        MethodSpec clearDefined = clearDefinedBuilder
                .addStatement("editor.$L", PreferenceEditorCommitStyle.APPLY.getStatementPart())
                .build();

        MethodSpec.Builder initDefaultsBuilder = MethodSpec.methodBuilder("initDefaults")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class);

        for (String preferenceKey : getterGenerator.getPreferenceKeys().keySet()) {
            if (putterGenerator.getPreferenceKeys().containsKey(preferenceKey)) {
                initDefaultsBuilder.addStatement("this.$L(this.$L())", preferenceKey, preferenceKey);
            }
        }


        MethodSpec initDefaults = initDefaultsBuilder.build();

        type.addMethod(get.build())
                .addMethod(contains.build())
                .addMethod(remove.build())
                .addMethod(registerListener.build())
                .addMethod(unregisterListener.build())
                .addMethod(clear.build())
                .addMethod(clearDefined)
                .addMethod(initDefaults);

        if (caching) {
            type.addMethod(resetCache.build());
        }
    }


    private void finish(Element interfaze, TypeSpec.Builder type) throws IOException {
        QualifiedNameable qualifiedNameable = (QualifiedNameable) interfaze;
        String[] split = qualifiedNameable.getQualifiedName().toString().split("\\.");
        String packageName = "";
        for (int i = 0; i < split.length - 1; i++) {
            packageName += split[i];
            if (i < split.length - 2) {
                packageName += ".";
            }
        }

        JavaFile javaFile = JavaFile.builder(packageName, type.build())
                .build();
        Filer filer = processingEnv.getFiler();
        javaFile.writeTo(filer);
    }


}
