/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.maven.packaging;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.camel.tooling.model.DataFormatModel;
import org.apache.camel.tooling.model.DataFormatModel.DataFormatOptionModel;
import org.apache.camel.tooling.model.EipModel;
import org.apache.camel.tooling.model.EipModel.EipOptionModel;
import org.apache.camel.tooling.model.JsonMapper;
import org.apache.camel.tooling.util.PackageHelper;
import org.apache.camel.tooling.util.Strings;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * Analyses the Camel plugins in a project and generates extra descriptor
 * information for easier auto-discovery in Camel.
 */
@Mojo(name = "generate-dataformats-list", threadSafe = true)
public class PackageDataFormatMojo extends AbstractGeneratorMojo {

    /**
     * The output directory for generated dataformats file
     */
    @Parameter(defaultValue = "${project.build.directory}/generated/camel/dataformats")
    protected File dataFormatOutDir;

    /**
     * The output directory for generated dataformats file
     */
    @Parameter(defaultValue = "${project.build.directory}/src/main/java")
    protected File configurerOutDir;

    /**
     * The output directory for generated dataformats file
     */
    @Parameter(defaultValue = "${project.build.directory}/classes")
    protected File schemaOutDir;

    /**
     * Execute goal.
     *
     * @throws org.apache.maven.plugin.MojoExecutionException execution of the
     *             main class or one of the threads it generated failed.
     * @throws org.apache.maven.plugin.MojoFailureException something bad
     *             happened...
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        prepareDataFormat(getLog(), project, projectHelper, dataFormatOutDir, configurerOutDir, schemaOutDir, buildContext);
    }

    public static int prepareDataFormat(Log log, MavenProject project, MavenProjectHelper projectHelper,
                                        File dataFormatOutDir, File configurerOutDir, File schemaOutDir, BuildContext buildContext)
        throws MojoExecutionException {

        File camelMetaDir = new File(dataFormatOutDir, "META-INF/services/org/apache/camel/");

        // first we need to setup the output directory because the next check
        // can stop the build before the end and eclipse always needs to know
        // about that directory
        if (projectHelper != null) {
            projectHelper.addResource(project, dataFormatOutDir.getPath(), Collections.singletonList("**/dataformat.properties"), Collections.emptyList());
        }

        if (!haveResourcesChanged(log, project, buildContext, "META-INF/services/org/apache/camel/dataformat")) {
            return 0;
        }

        Map<String, String> javaTypes = new HashMap<>();

        StringBuilder buffer = new StringBuilder();
        int count = 0;

        File f = new File(project.getBasedir(), "target/classes");
        f = new File(f, "META-INF/services/org/apache/camel/dataformat");
        if (f.exists() && f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File file : files) {
                    String javaType = readClassFromCamelResource(file, buffer, buildContext);
                    if (!file.isDirectory() && file.getName().charAt(0) != '.') {
                        count++;
                    }
                    if (javaType != null) {
                        javaTypes.put(file.getName(), javaType);
                    }
                }
            }
        }

        // is this from Apache Camel then the data format is out of the box and
        // we should enrich the json schema with more details
        boolean apacheCamel = "org.apache.camel".equals(project.getGroupId());

        // find camel-core and grab the data format model from there, and enrich
        // this model with information from this artifact
        // and create json schema model file for this data format
        try {
            if (apacheCamel && count > 0) {
                File core = PackageHelper.findCamelCoreDirectory(project.getBasedir());
                if (core != null) {
                    for (Map.Entry<String, String> entry : javaTypes.entrySet()) {
                        String name = entry.getKey();
                        String javaType = entry.getValue();
                        String modelName = asModelName(name);

                        String json = PackageHelper.loadText(new File(core, "target/classes/org/apache/camel/model/dataformat/"
                                + modelName + PackageHelper.JSON_SUFIX));

                        DataFormatModel dataFormatModel = extractDataFormatModel(project, json, name, javaType);
                        if (log.isDebugEnabled()) {
                            log.debug("Model: " + dataFormatModel);
                        }
                        String schema = JsonMapper.createParameterJsonSchema(dataFormatModel);
                        if (log.isDebugEnabled()) {
                            log.debug("JSon schema:\n" + schema);
                        }

                        // write this to the directory
                        Path out = schemaOutDir.toPath().resolve(schemaSubDirectory(dataFormatModel.getJavaType())).resolve(name + PackageHelper.JSON_SUFIX);
                        updateResource(buildContext, out, schema);

                        if (log.isDebugEnabled()) {
                            log.debug("Generated " + out + " containing JSon schema for " + name + " data format");
                        }


                        String type = dataFormatModel.getJavaType();
                        String cn = type.substring(type.lastIndexOf('.') + 1);
                        String pn = type.substring(0, type.length() - cn.length() - 1);
                        Path outCfg = configurerOutDir.toPath()
                                .resolve(pn.replace('.', '/'))
                                .resolve(cn + "Configurer.java");
//                        updateResource(buildContext, outCfg,
//                                generatePropertyConfigurer(pn, cn + "Configurer", cn,
//                                        dataFormatModel.get));
                    }
                } else {
                    throw new MojoExecutionException("Error finding core/camel-core/target/camel-core-engine-" + project.getVersion()
                                                     + ".jar file. Make sure camel-core has been built first.");
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Error loading dataformat model from camel-core. Reason: " + e, e);
        }

        if (count > 0) {
            String names = buffer.toString();
            Path outFile = camelMetaDir.toPath().resolve("dataformat.properties");
            String properties = createProperties(project, "dataFormats", names);
            updateResource(buildContext, outFile, properties);
            log.info("Generated " + outFile + " containing " + count + " Camel " + (count > 1 ? "dataformats: " : "dataformat: ") + names);
        } else {
            log.debug("No META-INF/services/org/apache/camel/dataformat directory found. Are you sure you have created a Camel data format?");
        }

        return count;
    }

    private static DataFormatModel extractDataFormatModel(MavenProject project, String json, String name, String javaType) {
        EipModel def = JsonMapper.generateEipModel(json);
        DataFormatModel model = new DataFormatModel();
        model.setName(name);
        model.setTitle(asModelTitle(name, def.getTitle()));
        model.setDescription(def.getDescription());
        model.setFirstVersion(asModelFirstVersion(name, def.getFirstVersion()));
        model.setLabel(def.getLabel());
        model.setDeprecated(def.isDeprecated());
        model.setDeprecationNote(def.getDeprecationNote());
        model.setJavaType(javaType);
        model.setModelName(def.getName());
        model.setModelJavaType(def.getJavaType());
        model.setGroupId(project.getGroupId());
        model.setArtifactId(project.getArtifactId());
        model.setVersion(project.getVersion());

        for (EipOptionModel opt : def.getOptions()) {
            DataFormatOptionModel option = new DataFormatOptionModel();
            option.setName(opt.getName());
            option.setKind(opt.getKind());
            option.setDisplayName(opt.getDisplayName());
            option.setGroup(opt.getGroup());
            option.setLabel(opt.getLabel());
            option.setRequired(opt.isRequired());
            option.setType(opt.getType());
            option.setJavaType(opt.getJavaType());
            option.setEnums(opt.getEnums());
            option.setOneOfs(opt.getOneOfs());
            option.setPrefix(opt.getPrefix());
            option.setOptionalPrefix(opt.getOptionalPrefix());
            option.setMultiValue(opt.isMultiValue());
            option.setDeprecated(opt.isDeprecated());
            option.setDeprecationNote(opt.getDeprecationNote());
            option.setSecret(opt.isSecret());
            option.setDefaultValue(opt.getDefaultValue());
            option.setDefaultValueNote(opt.getDefaultValueNote());
            option.setAsPredicate(opt.isAsPredicate());
            option.setConfigurationClass(opt.getConfigurationClass());
            option.setConfigurationField(opt.getConfigurationField());
            option.setDescription(opt.getDescription());

            if ("type".equals(option.getName()) && "bindy".equals(model.getModelName())) {
                switch (name) {
                    case "bindy-csv": option.setDefaultValue("Csv"); break;
                    case "bindy-fixed": option.setDefaultValue("Fixed"); break;
                    case "bindy-kvp": option.setDefaultValue("KeyValue"); break;
                }
            }
            if ("library".equals(option.getName()) && "json".equals(model.getModelName())) {
                switch (name) {
                    case "json-gson": option.setDefaultValue("Gson"); break;
                    case "json-jackson": option.setDefaultValue("Jackson"); break;
                    case "json-johnzon": option.setDefaultValue("Johnzon"); break;
                    case "json-fastson": option.setDefaultValue("Fastjson"); break;
                    case "json-xstream": option.setDefaultValue("XStream"); break;
                }
            }
            model.addOption(option);
        }
        return model;
    }

    private static String readClassFromCamelResource(File file, StringBuilder buffer, BuildContext buildContext) throws MojoExecutionException {
        // skip directories as there may be a sub .resolver directory
        if (file.isDirectory()) {
            return null;
        }
        String name = file.getName();
        if (name.charAt(0) != '.') {
            if (buffer.length() > 0) {
                buffer.append(" ");
            }
            buffer.append(name);
        }

        if (!buildContext.hasDelta(file)) {
            // if this file has not changed,
            // then no need to store the javatype
            // for the json file to be generated again
            // (but we do need the name above!)
            return null;
        }

        // find out the javaType for each data format
        try {
            String text = PackageHelper.loadText(file);
            Map<String, String> map = PackageHelper.parseAsMap(text);
            return map.get("class");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read file " + file + ". Reason: " + e, e);
        }
    }

    private static String asModelName(String name) {
        // special for some data formats
        if ("json-gson".equals(name) || "json-jackson".equals(name) || "json-johnzon".equals(name) || "json-xstream".equals(name) || "json-fastjson".equals(name)) {
            return "json";
        } else if ("bindy-csv".equals(name) || "bindy-fixed".equals(name) || "bindy-kvp".equals(name)) {
            return "bindy";
        } else if ("yaml-snakeyaml".equals(name)) {
            return "yaml";
        }
        return name;
    }

    private static String asModelFirstVersion(String name, String firstVersion) {
        switch (name) {
            case "json-gson": return "2.10.0";
            case "json-jackson": return "2.0.0";
            case "json-johnzon": return "2.18.0";
            case "json-xstream": return "2.0.0";
            case "json-fastjson": return "2.20.0";
            default: return firstVersion;
        }
    }

    // TODO: split json / bindy into multiple jsons descriptors
    private static String asModelTitle(String name, String title) {
        // special for some data formats
        if ("json-gson".equals(name)) {
            return "JSon GSon";
        } else if ("json-jackson".equals(name)) {
            return "JSon Jackson";
        } else if ("json-johnzon".equals(name)) {
            return "JSon Johnzon";
        } else if ("json-xstream".equals(name)) {
            return "JSon XStream";
        } else if ("json-fastjson".equals(name)) {
            return "JSon Fastjson";
        } else if ("bindy-csv".equals(name)) {
            return "Bindy CSV";
        } else if ("bindy-fixed".equals(name)) {
            return "Bindy Fixed Length";
        } else if ("bindy-kvp".equals(name)) {
            return "Bindy Key Value Pair";
        } else if ("yaml-snakeyaml".equals(name)) {
            return "YAML SnakeYAML";
        }
        return title;
    }

    private static String schemaSubDirectory(String javaType) {
        int idx = javaType.lastIndexOf('.');
        String pckName = javaType.substring(0, idx);
        return pckName.replace('.', '/');
    }

    public static String generatePropertyConfigurer(
            String pn, String cn, String en,
            Set<DataFormatOptionModel> options) throws IOException {

        try (StringWriter w = new StringWriter()) {
            w.write("/* Generated by camel-package-maven-plugin - do not edit this file! */\n");
            w.write("package " + pn + ";\n");
            w.write("\n");
            w.write("import java.util.HashMap;\n");
            w.write("import java.util.Map;\n");
            w.write("\n");
            w.write("import org.apache.camel.CamelContext;\n");
            w.write("import org.apache.camel.spi.GeneratedPropertyConfigurer;\n");
            w.write("import org.apache.camel.support.component.PropertyConfigurerSupport;\n");
            w.write("\n");
            w.write("/**\n");
            w.write(" * Source code generated by camel-package-maven-plugin - do not edit this file!\n");
            w.write(" */\n");
            w.write("@SuppressWarnings(\"unchecked\")\n");
            w.write("public class " + cn + " extends PropertyConfigurerSupport implements GeneratedPropertyConfigurer {\n");
            w.write("\n");
            w.write("    @Override\n");
            w.write("    public boolean configure(CamelContext camelContext, Object target, String name, Object value, boolean ignoreCase) {\n");
            w.write("        switch (ignoreCase ? name.toLowerCase() : name) {\n");
            for (DataFormatOptionModel option : options) {
                String name = option.getName();
                String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
                String type = Strings.canonicalClassName(option.getType());
                w.write(String.format("        case \"%s\": ((%s) target).%s(property(camelContext, %s.class, value)); return true;\n",
                        name, en, setter, type));
                if (!name.toLowerCase().equals(name)) {
                    w.write(String.format("        case \"%s\": ((%s) target).%s(property(camelContext, %s.class, value)); return true;\n",
                            name.toLowerCase(), en, setter, type));
                }
            }
            w.write("            default: return false;\n");
            w.write("        }\n");
            w.write("    }\n");
            w.write("\n");
            w.write("}\n");
            w.write("\n");
            return w.toString();
        }
    }

    public static String generateMetaInfConfigurer(String name, String fqn) {
        return "# Generated by camel annotation processor\n" + fqn + "\n";
    }

}
