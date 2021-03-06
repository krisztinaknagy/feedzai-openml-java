/*
 * Copyright 2018 Feedzai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.feedzai.openml.datarobot;

import com.datarobot.prediction.Predictor;
import com.feedzai.openml.data.schema.AbstractValueSchema;
import com.feedzai.openml.data.schema.CategoricalValueSchema;
import com.feedzai.openml.data.schema.DatasetSchema;
import com.feedzai.openml.java.utils.JavaFileUtils;
import com.feedzai.openml.model.MachineLearningModel;
import com.feedzai.openml.provider.descriptor.fieldtype.ParamValidationError;
import com.feedzai.openml.provider.exception.ModelLoadingException;
import com.feedzai.openml.provider.model.MachineLearningModelLoader;
import com.feedzai.openml.util.load.LoadModelUtils;
import com.feedzai.openml.util.load.LoadSchemaUtils;
import com.feedzai.openml.util.validate.ClassificationValidationUtils;
import com.feedzai.openml.util.validate.ValidationUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import javafx.util.Pair;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Implementation of the {@link MachineLearningModelLoader}.
 * <p>
 * This class is responsible for the initialization of a {@link MachineLearningModel} that was generated in DataRobot.
 *
 * @author Paulo Pereira (paulo.pereira@feedzai.com)
 * @since 0.1.0
 */
public class DataRobotModelCreator implements MachineLearningModelLoader<ClassificationBinaryDataRobotModel> {

    /**
     * Logger for this class.
     */
    private static final Logger logger = LoggerFactory.getLogger(DataRobotModelCreator.class);

    /**
     * Template of the package generated by DataRobot that contains the model to import.
     */
    private static final String MODEL_PACKAGE_TEMPLATE = "com.datarobot.prediction.dr%s.DRModel";

    /**
     * This is useful since DR uses the following hard-coded values regardless of the actual boolean values (e.g.,
     * "True" will be assumed even if the data has "true" or "TRUE").
     *
     * @since 0.5.2
     */
    private static final Set<String> BOOLEAN_VALUES = ImmutableSet.of("True", "False");

    /**
     * A function that tells whether the given target variable values are for a binary boolean DR model.
     *
     * @see #BOOLEAN_VALUES
     *
     * @since 0.5.2
     */
    private static final Predicate<String[]> IS_BOOLEAN_MODEL = possibleVals ->
            Objects.equals(BOOLEAN_VALUES, Arrays.stream(possibleVals).collect(Collectors.toSet()));

    /**
     * {@inheritDoc}
     *
     * This provider assumes the {@code modelPath} is a directory.
     */
    @Override
    public ClassificationBinaryDataRobotModel loadModel(final Path modelPath,
                                                        final DatasetSchema schema) throws ModelLoadingException {

        logger.info("Trying to load a model in path [{}]...", modelPath);
        ClassificationValidationUtils.validateParamsModelToLoad(this, modelPath, schema, ImmutableMap.of());

        final Pair<Predictor, URLClassLoader> predictorPair = createPredictorInstance(modelPath);
        final Predictor predictor = predictorPair.getKey();

        final int predictorSize = predictor.get_double_predictors().length + predictor.get_string_predictors().length;
        // We ignore the target variable in the schema for schema matching purposes.
        if (predictorSize != schema.getFieldSchemas().size() - 1) {
            final String errorMsg = String.format(
                    "Wrong number of fields in the given schema. The model expected %d feature fields + 1 target field," +
                            " but the schema had a total of %d fields only (encompassing both features and target fields).",
                    predictorSize,
                    schema.getFieldSchemas().size()
            );
            final String extraMsg = String.format(
                    " Schema expected by the model %s. Schema provided %s.",
                    predictor2Str(predictor),
                    schema
            );
            logger.error(errorMsg + extraMsg);
            throw new ModelLoadingException(errorMsg);
        }

        final String[] targetModelValues = getTargetModelValues(predictor);
        final SortedSet<String> nominalValues = checkTargetModelValuesWithSchema(schema, targetModelValues);

        final ClassificationBinaryDataRobotModel resultingModel = new ClassificationBinaryDataRobotModel(
                predictor,
                nominalValues.first().equals(targetModelValues[0]),
                modelPath,
                schema,
                predictorPair.getValue()
        );

        ClassificationValidationUtils.validateClassificationModel(schema, resultingModel);

        logger.info("Model in path [{}] loaded successfully.", modelPath);
        return resultingModel;
    }

    /**
     * Converts a predictor to string.
     *
     * @param predictor The predictor.
     * @return The string.
     * @since 0.5.1
     */
    private String predictor2Str(final Predictor predictor) {
        final StringBuilder stringBuilder = new StringBuilder();

        for (final String doublePredName : predictor.get_double_predictors()) {
            stringBuilder.append(doublePredName);
            stringBuilder.append(",");
        }

        for (final String strPredName : predictor.get_string_predictors()) {
            stringBuilder.append(strPredName);
            stringBuilder.append(",");
        }

        return stringBuilder.toString();
    }

    /**
     * Creates a predictor instance for the binary model generated in DataRobot.
     *
     * @param modelPath The path from where the model was initially loaded.
     * @return The predictor for the binary model generated in DataRobot.
     * @throws ModelLoadingException If there is a problem creating the the predictor.
     */
    private Pair<Predictor, URLClassLoader> createPredictorInstance(final Path modelPath) throws ModelLoadingException {
        final String modelFilePath = LoadModelUtils.getModelFilePath(modelPath).toAbsolutePath().toString();
        final URLClassLoader urlClassLoader = JavaFileUtils.getUrlClassLoader(
                modelFilePath,
                ClassificationBinaryDataRobotModel.class.getClassLoader()
        );
        return new Pair<>(
                (Predictor) JavaFileUtils.createNewInstanceFromClassLoader(
                        modelFilePath,
                        MODEL_PACKAGE_TEMPLATE,
                        urlClassLoader
                ),
                urlClassLoader
        );
    }

    /**
     * Gets the values of the target field used to train the DataRobot model.
     *
     * @param predictor Predictor for the binary model generated in DataRobot.
     * @return The target values used to train the model.
     * @throws ModelLoadingException If the binary of the model is an older non-supported DataRobot model.
     */
    private String[] getTargetModelValues(final Predictor predictor) throws ModelLoadingException {
        final String targetVarField = "classLabels";
        try {
            return (String[]) FieldUtils.readField(predictor, targetVarField, true);
        } catch (final Exception e) {
            final String errorMsg = String.format(
                    "Jar file of the DataRobot model is not supported. A possible cause is that the model might be to " +
                            "old and a newer version is required because it lacks the \"%s\" field with the target " +
                            "values. If that is the cause create a new project on DataRobot and train new models.",
                    targetVarField
            );
            logger.error(errorMsg, e);
            throw new ModelLoadingException(errorMsg, e);
        }
    }

    /**
     * Check that the target values used to train the DataRobot model are compatible with the ones declared in the schema.
     *
     * @param schema            The {@link DatasetSchema} the model uses.
     * @param targetModelValues Target values used to train the model.
     * @return The nominal values of the target field declared in the schema.
     * @throws ModelLoadingException If the target values are incompatible.
     */
    SortedSet<String> checkTargetModelValuesWithSchema(final DatasetSchema schema,
                                                       final String[] targetModelValues) throws ModelLoadingException {

        final SortedSet<String> nominalValues = ((CategoricalValueSchema) schema.getTargetFieldSchema().getValueSchema())
                .getNominalValues();

        if (IS_BOOLEAN_MODEL.test(targetModelValues)) {
            if (Objects.equals(
                    nominalValues.stream().map(StringUtils::lowerCase).collect(Collectors.toSet()),
                    BOOLEAN_VALUES.stream().map(StringUtils::lowerCase).collect(Collectors.toSet())
            )) {
                return new TreeSet<>(BOOLEAN_VALUES);
            }

            final String delimiter = ",";
            final String errorMsg = String.format(
                    "Incompatible target values. The model is binary and thus expects some form of: [%s], but the schema had: %s.",
                    String.join(delimiter, targetModelValues),
                    String.join(delimiter, nominalValues)
            );
            logger.error(errorMsg);
            throw new ModelLoadingException(errorMsg);
        }

        if (nominalValues.size() != targetModelValues.length ||
                !nominalValues.containsAll(Arrays.asList(targetModelValues))) {
            final String delimiter = ",";
            final String errorMsg = String.format(
                    "Incompatible target values. model: [%s], schema: %s.",
                    String.join(delimiter, targetModelValues),
                    String.join(delimiter, nominalValues)
            );
            logger.error(errorMsg);
            throw new ModelLoadingException(errorMsg);
        }
        return nominalValues;
    }

    @Override
    public DatasetSchema loadSchema(final Path modelPath) throws ModelLoadingException {
        return LoadSchemaUtils.datasetSchemaFromJson(modelPath);
    }

    /**
     * Validates that the target field only has two possible values.
     *
     * @param schema The {@link DatasetSchema} the model uses.
     * @return A list of {@link ParamValidationError} with the problems/error found during the validation.
     */
    List<ParamValidationError> validateTargetIsBinary(final DatasetSchema schema) {
        final ImmutableList.Builder<ParamValidationError> validationErrors = ImmutableList.builder();
        final AbstractValueSchema valueSchema = schema.getTargetFieldSchema().getValueSchema();
        if (valueSchema instanceof CategoricalValueSchema) {
            final int nominalValuesSize = ((CategoricalValueSchema) valueSchema).getNominalValues().size();
            if (nominalValuesSize != 2) {
                validationErrors.add(
                        new ParamValidationError("At the moment only binary classification models are supported")
                );
            }
        }
        return validationErrors.build();
    }

    /**
     * Validates that the model file is in the expected file format (jar).
     *
     * @param modelPath The path from where the model was initially loaded.
     * @return A list of {@link ParamValidationError} with the problems/error found during the validation.
     */
    List<ParamValidationError> validateModelFileFormat(final Path modelPath) {
        final ImmutableList.Builder<ParamValidationError> validationErrors = ImmutableList.builder();
        try {
            final String modelFilePath = LoadModelUtils.getModelFilePath(modelPath).toAbsolutePath().toString();
            if (!JavaFileUtils.isJarFile(modelFilePath)) {
                validationErrors.add(
                        new ParamValidationError(
                                String.format(
                                        "Extension [%s] not recognized for a DataRobot model, the model should be" +
                                                "exported in the [%s] extension.",
                                        modelFilePath,
                                        JavaFileUtils.JAR_EXTENSION
                                ))
                );
            }
        } catch (ModelLoadingException e) {
            validationErrors.add(
                    new ParamValidationError(
                            String.format(
                                    "Unable to find a model file in [%s].",
                                    modelPath
                            ))
            );
        }
        return validationErrors.build();
    }

    @Override
    public List<ParamValidationError> validateForLoad(final Path modelPath,
                                                      final DatasetSchema schema,
                                                      final Map<String, String> params) {

        final ImmutableList.Builder<ParamValidationError> errorBuilder = ImmutableList.builder();

        errorBuilder.addAll(ValidationUtils.baseLoadValidations(schema, params));
        errorBuilder.addAll(ValidationUtils.validateModelInDir(modelPath));

        ValidationUtils.validateCategoricalSchema(schema).ifPresent(errorBuilder::add);

        errorBuilder.addAll(validateModelFileFormat(modelPath));
        errorBuilder.addAll(validateTargetIsBinary(schema));

        return errorBuilder.build();
    }
}
