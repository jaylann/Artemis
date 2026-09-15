import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import de.tum.cit.aet.artemis.atlas.service.ContentExtractionService;
import de.tum.cit.aet.artemis.atlas.domain.LearningObject;
import de.tum.cit.aet.artemis.fileupload.dto.FileUploadExerciseInputDTO;
import de.tum.cit.aet.artemis.lecture.domain.AttachmentVideoUnit;
import de.tum.cit.aet.artemis.lecture.domain.OnlineUnit;
import de.tum.cit.aet.artemis.lecture.domain.TextUnit;
import de.tum.cit.aet.artemis.lecture.dto.AttachmentVideoUnitDTO;
import de.tum.cit.aet.artemis.lecture.dto.OnlineUnitDTO;
import de.tum.cit.aet.artemis.lecture.dto.TextUnitDTO;
import de.tum.cit.aet.artemis.modeling.domain.ModelingExercise;
import de.tum.cit.aet.artemis.modeling.dto.UpdateModelingExerciseDTO;
import de.tum.cit.aet.artemis.programming.dto.CreateProgrammingExerciseDTO;
import de.tum.cit.aet.artemis.quiz.dto.exercise.QuizExerciseCreateDTO;
import de.tum.cit.aet.artemis.text.domain.TextExercise;
import de.tum.cit.aet.artemis.text.dto.UpdateTextExerciseDTO;

/** Offline DTO and real extractor check. No database, HTTP client, or flavor-strip invocation. */
class VerifyHeterogeneousFixture {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var extractor = new ContentExtractionService(null, null, null, "gpt-5.6-luna", "medium", 1.0);
        var rows = new ArrayList<Map<String, Object>>();
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (JsonNode row : mapper.readTree(Files.readString(Path.of(args[0])))) {
                JsonNode body = row.get("payload");
                String type = row.get("type").asText();
                Object dto;
                LearningObject object;
                if (row.get("kind").asText().equals("exercise")) {
                    switch (type) {
                        case "programming" -> {
                            var value = mapper.treeToValue(body, CreateProgrammingExerciseDTO.class);
                            dto = value;
                            object = value.toEntity();
                        }
                        case "text" -> {
                            var value = mapper.treeToValue(body, UpdateTextExerciseDTO.class);
                            dto = value;
                            var exercise = new TextExercise();
                            exercise.setTitle(value.title());
                            exercise.setProblemStatement(value.problemStatement());
                            exercise.setExampleSolution(value.exampleSolution());
                            object = exercise;
                        }
                        case "modeling" -> {
                            var value = mapper.treeToValue(body, UpdateModelingExerciseDTO.class);
                            dto = value;
                            var exercise = new ModelingExercise();
                            exercise.setTitle(value.title());
                            exercise.setProblemStatement(value.problemStatement());
                            exercise.setExampleSolutionExplanation(value.exampleSolutionExplanation());
                            exercise.setDiagramType(value.diagramType());
                            object = exercise;
                        }
                        case "file-upload" -> {
                            var value = mapper.treeToValue(body, FileUploadExerciseInputDTO.class);
                            dto = value;
                            object = value.toEntity();
                        }
                        case "quiz" -> {
                            var value = mapper.treeToValue(body, QuizExerciseCreateDTO.class);
                            dto = value;
                            object = value.toDomainObject();
                        }
                        default -> throw new IllegalArgumentException(type);
                    }
                }
                else {
                    switch (type) {
                        case "text" -> {
                            var value = mapper.treeToValue(body, TextUnitDTO.class);
                            dto = value;
                            var unit = new TextUnit();
                            unit.setName(value.name());
                            unit.setContent(value.content());
                            object = unit;
                        }
                        case "online" -> {
                            var value = mapper.treeToValue(body, OnlineUnitDTO.class);
                            dto = value;
                            var unit = new OnlineUnit();
                            unit.setName(value.name());
                            unit.setDescription(value.description());
                            unit.setSource(value.source());
                            object = unit;
                        }
                        case "attachment-video" -> {
                            var value = mapper.treeToValue(body, AttachmentVideoUnitDTO.class);
                            dto = value;
                            var unit = new AttachmentVideoUnit();
                            unit.setName(value.name());
                            unit.setDescription(value.description());
                            object = unit;
                        }
                        default -> throw new IllegalArgumentException(type);
                    }
                }
                var violations = validator.validate(dto);
                if (!violations.isEmpty()) {
                    throw new IllegalArgumentException(row.get("key") + ": " + violations);
                }
                if (object instanceof de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise exercise) {
                    exercise.validateGeneralSettings();
                    exercise.validateProgrammingSettings();
                }
                if (object instanceof de.tum.cit.aet.artemis.quiz.domain.QuizExercise exercise) {
                    if (exercise.getQuizQuestions().stream().anyMatch(question -> !question.isValid())) {
                        throw new IllegalArgumentException(row.get("key") + ": invalid quiz question");
                    }
                }
                var extracted = extractor.extractContent(object, false);
                if (extracted.extractedLearningText().isBlank()) {
                    throw new IllegalArgumentException(row.get("key") + ": empty extracted content");
                }
                var result = new LinkedHashMap<String, Object>();
                result.put("key", row.get("key").asText());
                result.put("phase", row.get("phase").asText());
                result.put("kind", row.get("kind").asText());
                result.put("type", type);
                result.put("characters", extracted.extractedLearningText().length());
                result.put("learningText", extracted.extractedLearningText());
                result.put("metadata", extracted.metadata());
                rows.add(result);
            }
        }
        if (rows.size() != 144) {
            throw new IllegalArgumentException("Expected 144 initial/revised fixture cases, got " + rows.size());
        }
        Files.writeString(Path.of(args[1]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows) + "\n");
        System.out.println("Validated " + rows.size() + " DTO/extraction cases without provider calls or database persistence.");
    }
}
