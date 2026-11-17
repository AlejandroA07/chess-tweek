package com.chesstweek.training.internal.content;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import com.chesstweek.training.GuidedCourses;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

@Component
public final class CourseContentLoader {

    private static final String RESOURCE = "/training/grandmaster-thinking-method.json";
    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    private static final Pattern PUZZLE_ID = Pattern.compile("[A-Za-z0-9]{5,8}");
    private static final Pattern GAME_URL = Pattern.compile(
            "https://lichess\\.org/game/[A-Za-z0-9]{8}(?:/(?:white|black))?#\\d+");
    private static final Pattern THEME = Pattern.compile("[A-Za-z0-9]{1,40}");

    private final CourseContent content;

    public CourseContentLoader(ObjectMapper mapper) {
        this.content = load(mapper);
    }

    public CourseContent content() {
        return content;
    }

    private static CourseContent load(ObjectMapper mapper) {
        try (InputStream input = CourseContentLoader.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Guided course content is missing");
            }
            return validateAndMap(mapper.readValue(input, CourseDefinition.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Guided course content could not be loaded", exception);
        }
    }

    private static CourseContent validateAndMap(CourseDefinition definition) {
        requireKey(definition.key(), "course key");
        if (!GuidedCourses.COURSE_KEY.equals(definition.key())
                || definition.version() < 1
                || definition.version() > 1000) {
            throw new IllegalStateException("Guided course identity is invalid");
        }
        requireText(definition.title(), 1, 100, "course title");
        requireText(definition.subtitle(), 1, 180, "course subtitle");
        requireText(definition.attribution(), 1, 400, "course attribution");
        if (definition.modules() == null || definition.modules().isEmpty()) {
            throw new IllegalStateException("Guided course needs modules");
        }

        Set<String> keys = new HashSet<>();
        Map<String, GuidedCourses.Lesson> lessons = new HashMap<>();
        Map<String, CourseContent.ExerciseAnswer> answers = new HashMap<>();
        List<GuidedCourses.CourseModule> modules = new ArrayList<>();
        for (CourseDefinition.ModuleDefinition module : definition.modules()) {
            requireUniqueKey(module.key(), keys, "module");
            requireText(module.title(), 1, 100, "module title");
            requireText(module.description(), 1, 300, "module description");
            GuidedCourses.ModuleStatus status = status(module.status());
            List<CourseDefinition.LessonDefinition> sourceLessons =
                    module.lessons() == null ? List.of() : module.lessons();
            if (status == GuidedCourses.ModuleStatus.PLANNED && !sourceLessons.isEmpty()) {
                throw new IllegalStateException("Planned modules cannot expose unfinished lessons");
            }
            List<GuidedCourses.Lesson> mappedLessons = new ArrayList<>();
            for (CourseDefinition.LessonDefinition lesson : sourceLessons) {
                GuidedCourses.Lesson mapped = mapLesson(lesson, keys, answers);
                lessons.put(mapped.key(), mapped);
                mappedLessons.add(mapped);
            }
            if (status == GuidedCourses.ModuleStatus.AVAILABLE && mappedLessons.isEmpty()) {
                throw new IllegalStateException("Available modules need lessons");
            }
            modules.add(new GuidedCourses.CourseModule(
                    module.key(),
                    module.title(),
                    module.description(),
                    status,
                    List.copyOf(mappedLessons)));
        }
        GuidedCourses.Course course = new GuidedCourses.Course(
                definition.key(),
                definition.version(),
                definition.title(),
                definition.subtitle(),
                definition.attribution(),
                List.copyOf(modules));
        return new CourseContent(course, Map.copyOf(lessons), Map.copyOf(answers));
    }

    private static GuidedCourses.Lesson mapLesson(
            CourseDefinition.LessonDefinition lesson,
            Set<String> keys,
            Map<String, CourseContent.ExerciseAnswer> answers) {
        requireUniqueKey(lesson.key(), keys, "lesson");
        requireText(lesson.title(), 1, 100, "lesson title");
        requireText(lesson.summary(), 1, 300, "lesson summary");
        if (lesson.estimatedMinutes() < 1 || lesson.estimatedMinutes() > 120) {
            throw new IllegalStateException("Lesson duration is invalid");
        }
        List<String> explanation = textList(lesson.explanation(), 1, 8, 500, "explanation");
        List<String> checklist = textList(lesson.checklist(), 1, 8, 180, "checklist");
        if (lesson.exercises() == null || lesson.exercises().isEmpty()) {
            throw new IllegalStateException("Available lessons need exercises");
        }
        List<GuidedCourses.Exercise> exercises = new ArrayList<>();
        for (CourseDefinition.ExerciseDefinition exercise : lesson.exercises()) {
            requireUniqueKey(exercise.key(), keys, "exercise");
            requireText(exercise.fen(), 20, 100, "exercise FEN");
            requireText(exercise.prompt(), 1, 300, "exercise prompt");
            GuidedCourses.Orientation orientation = orientation(exercise.orientation());
            GuidedCourses.DifficultyTier difficultyTier = difficultyTier(exercise.difficultyTier());
            List<String> acceptedMoves = validateMoves(exercise.fen(), exercise.acceptedMoves());
            List<String> solutionLine = validateLine(
                    exercise.fen(), exercise.solutionLine(), difficultyTier, acceptedMoves);
            CourseContent.SourceMetadata source = source(exercise.source(), difficultyTier);
            List<String> hints = textList(exercise.hints(), 2, 2, 180, "exercise hints");
            requireText(exercise.correctFeedback(), 1, 300, "correct feedback");
            requireText(exercise.incorrectFeedback(), 1, 300, "incorrect feedback");
            requireText(exercise.explanation(), 1, 500, "exercise explanation");
            exercises.add(new GuidedCourses.Exercise(
                    exercise.key(),
                    exercise.fen(),
                    orientation,
                    difficultyTier,
                    source.ratingAtSelection(),
                    exercise.prompt()));
            answers.put(
                    exercise.key(),
                    new CourseContent.ExerciseAnswer(
                            lesson.key(),
                            exercise.fen(),
                            acceptedMoves,
                            solutionLine,
                            hints,
                            exercise.correctFeedback(),
                            exercise.incorrectFeedback(),
                            exercise.explanation(),
                            source));
        }
        return new GuidedCourses.Lesson(
                lesson.key(),
                lesson.title(),
                lesson.summary(),
                lesson.estimatedMinutes(),
                explanation,
                checklist,
                List.copyOf(exercises));
    }

    private static List<String> validateMoves(String fen, List<String> moves) {
        if (moves == null || moves.isEmpty() || moves.size() > 3) {
            throw new IllegalStateException("Exercise needs one to three accepted moves");
        }
        ChessGame game;
        try {
            game = new ChessGame(fen);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Exercise FEN is invalid", exception);
        }
        List<String> result = new ArrayList<>();
        for (String move : moves) {
            if (move == null || !UCI.matcher(move).matches()) {
                throw new IllegalStateException("Exercise move is invalid");
            }
            try {
                if (!game.getLegalMoves().contains(game.getMove(NotationType.UCI, move))) {
                    throw new IllegalStateException("Exercise solution is illegal: " + move);
                }
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Exercise solution is illegal: " + move, exception);
            }
            result.add(move);
        }
        return List.copyOf(result);
    }

    private static List<String> validateLine(
            String fen,
            List<String> moves,
            GuidedCourses.DifficultyTier difficultyTier,
            List<String> acceptedMoves) {
        int minimum = switch (difficultyTier) {
            case FOUNDATION -> 3;
            case DEVELOPMENT -> 5;
            case STRETCH -> 7;
        };
        if (moves == null || moves.size() < minimum || moves.size() > 15
                || !acceptedMoves.contains(moves.getFirst())) {
            throw new IllegalStateException("Exercise solution line is invalid");
        }
        try {
            ChessGame game = new ChessGame(fen);
            for (String uci : moves) {
                if (uci == null || !UCI.matcher(uci).matches()) {
                    throw new IllegalStateException("Exercise solution line is invalid");
                }
                Move move = game.getMove(NotationType.UCI, uci);
                if (move == null || !game.isLegalMove(move)) {
                    throw new IllegalStateException("Exercise solution line is illegal: " + uci);
                }
                game.playMove(move);
            }
            return List.copyOf(moves);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Exercise solution line is illegal", exception);
        }
    }

    private static CourseContent.SourceMetadata source(
            CourseDefinition.SourceDefinition source,
            GuidedCourses.DifficultyTier difficultyTier) {
        if (source == null
                || !"lichess".equals(source.provider())
                || !"CC0-1.0".equals(source.license())
                || source.puzzleId() == null
                || !PUZZLE_ID.matcher(source.puzzleId()).matches()
                || source.gameUrl() == null
                || !GAME_URL.matcher(source.gameUrl()).matches()
                || source.ratingDeviationAtSelection() < 0
                || source.ratingDeviationAtSelection() > 500
                || source.popularityAtSelection() < -100
                || source.popularityAtSelection() > 100
                || source.playsAtSelection() < 0
                || !ratingMatches(difficultyTier, source.ratingAtSelection())
                || source.themes() == null
                || source.themes().isEmpty()
                || source.themes().size() > 30
                || source.themes().stream().anyMatch(theme -> theme == null || !THEME.matcher(theme).matches())) {
            throw new IllegalStateException("Exercise source metadata is invalid");
        }
        try {
            LocalDate.parse(source.snapshotDate());
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalStateException("Exercise source snapshot date is invalid", exception);
        }
        return new CourseContent.SourceMetadata(
                source.provider(),
                source.puzzleId(),
                source.gameUrl(),
                source.license(),
                source.ratingAtSelection(),
                source.ratingDeviationAtSelection(),
                source.popularityAtSelection(),
                source.playsAtSelection(),
                List.copyOf(source.themes()),
                source.snapshotDate());
    }

    private static boolean ratingMatches(
            GuidedCourses.DifficultyTier difficultyTier, int rating) {
        return switch (difficultyTier) {
            case FOUNDATION -> rating >= 1500 && rating <= 1700;
            case DEVELOPMENT -> rating >= 1750 && rating <= 1950;
            case STRETCH -> rating >= 2000 && rating <= 2200;
        };
    }

    private static GuidedCourses.ModuleStatus status(String value) {
        if (value == null) {
            throw new IllegalStateException("Module status is missing");
        }
        try {
            return GuidedCourses.ModuleStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Module status is invalid", exception);
        }
    }

    private static GuidedCourses.Orientation orientation(String value) {
        if (value == null) {
            throw new IllegalStateException("Exercise orientation is missing");
        }
        try {
            return GuidedCourses.Orientation.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Exercise orientation is invalid", exception);
        }
    }

    private static GuidedCourses.DifficultyTier difficultyTier(String value) {
        if (value == null) {
            throw new IllegalStateException("Exercise difficulty tier is missing");
        }
        try {
            return GuidedCourses.DifficultyTier.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Exercise difficulty tier is invalid", exception);
        }
    }

    private static List<String> textList(
            List<String> values, int minimum, int maximum, int maximumLength, String name) {
        if (values == null || values.size() < minimum || values.size() > maximum) {
            throw new IllegalStateException(name + " count is invalid");
        }
        values.forEach(value -> requireText(value, 1, maximumLength, name));
        return List.copyOf(values);
    }

    private static void requireUniqueKey(String value, Set<String> keys, String name) {
        requireKey(value, name + " key");
        if (!keys.add(value)) {
            throw new IllegalStateException("Duplicate " + name + " key");
        }
    }

    private static void requireKey(String value, String name) {
        if (value == null || value.length() > 64 || !KEY.matcher(value).matches()) {
            throw new IllegalStateException(name + " is invalid");
        }
    }

    private static void requireText(String value, int minimum, int maximum, String name) {
        if (value == null || value.length() < minimum || value.length() > maximum) {
            throw new IllegalStateException(name + " is invalid");
        }
    }
}
