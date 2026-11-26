package com.chesstweek.training.internal.performance;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chesstweek.training.PatternLadders;
import com.chesstweek.training.PuzzleTraining;
import com.chesstweek.training.internal.persistence.JdbcPatternLadderStore;

/**
 * Pattern ladders over the shared Puzzle training: the store picks the next Puzzle in rating
 * order and the attempt is an ordinary category attempt of {@link PuzzleTrainingService}.
 */
@Service
public class PatternLadderService implements PatternLadders {

    private static final java.util.regex.Pattern ACCOUNT_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final java.util.regex.Pattern PATTERN_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,120}");

    private final JdbcPatternLadderStore store;
    private final PuzzleTrainingService training;
    private final Clock clock;
    private final RandomGenerator random;

    @Autowired
    public PatternLadderService(JdbcPatternLadderStore store, PuzzleTrainingService training) {
        this(store, training, Clock.systemUTC(), new SecureRandom());
    }

    PatternLadderService(
            JdbcPatternLadderStore store, PuzzleTrainingService training, Clock clock,
            RandomGenerator random) {
        this.store = store;
        this.training = training;
        this.clock = clock;
        this.random = random;
    }

    @Override
    public List<PatternLadders.Pattern> patterns() {
        return List.copyOf(store.patterns());
    }

    @Override
    public Optional<Ladder> ladder(String accountId, String patternKey) {
        requireAccount(accountId);
        return category(patternKey).map(category -> {
            Map<RatingPackage, Integer> sizes = store.packageSizes(category.categoryId());
            Map<RatingPackage, Progress> progress = store.progress(accountId, category.categoryId());
            List<PackageEntry> packages = new ArrayList<>();
            for (RatingPackage ratingPackage : RatingPackage.values()) {
                packages.add(new PackageEntry(
                        ratingPackage, sizes.get(ratingPackage), progress.get(ratingPackage)));
            }
            return new Ladder(category.pattern(), List.copyOf(packages));
        });
    }

    @Override
    public Next next(String accountId, String patternKey, RatingPackage ratingPackage) {
        requireAccount(accountId);
        JdbcPatternLadderStore.PatternCategory category = category(patternKey).orElse(null);
        if (category == null || ratingPackage == null) {
            return new Next(NextOutcome.NOT_FOUND, null, null);
        }
        while (true) {
            JdbcPatternLadderStore.Advance advance = store.advance(
                    accountId, category.categoryId(), ratingPackage, clock.millis()).orElse(null);
            if (advance == null) {
                return new Next(NextOutcome.COMPLETE, null, null);
            }
            // A Puzzle removed between the seek and the start is skipped; the place has moved past it.
            Optional<PuzzleTraining.Attempt> attempt =
                    training.startInCategory(accountId, advance.puzzleId());
            if (attempt.isPresent()) {
                return new Next(NextOutcome.STARTED, attempt.get(), advance.progress());
            }
        }
    }

    @Override
    public boolean restart(String accountId, String patternKey, RatingPackage ratingPackage) {
        requireAccount(accountId);
        JdbcPatternLadderStore.PatternCategory category = category(patternKey).orElse(null);
        if (category == null || ratingPackage == null) {
            return false;
        }
        store.restart(accountId, category.categoryId(), ratingPackage);
        return true;
    }

    @Override
    public JumpOutcome jump(
            String accountId, String patternKey, RatingPackage ratingPackage, int position) {
        requireAccount(accountId);
        JdbcPatternLadderStore.PatternCategory category = category(patternKey).orElse(null);
        if (category == null || ratingPackage == null) {
            return JumpOutcome.NOT_FOUND;
        }
        int size = store.packageSizes(category.categoryId()).get(ratingPackage);
        if (position < 1 || position > size) {
            return JumpOutcome.OUT_OF_RANGE;
        }
        return store.jump(accountId, category.categoryId(), ratingPackage, position, clock.millis())
                ? JumpOutcome.MOVED
                : JumpOutcome.OUT_OF_RANGE;
    }

    @Override
    public Optional<FastLadder> startFast(String accountId, String patternKey) {
        requireAccount(accountId);
        JdbcPatternLadderStore.PatternCategory category = category(patternKey).orElse(null);
        if (category == null) {
            return Optional.empty();
        }
        int categoryId = category.categoryId();
        Set<Long> chosen = new HashSet<>();
        List<JdbcPatternLadderStore.FastItem> items = new ArrayList<>();
        for (RatingRange step : FAST_STEPS) {
            store.randomInRange(accountId, categoryId, step.low(), step.high(), chosen, random)
                    .ifPresent(puzzleId -> {
                        chosen.add(puzzleId);
                        items.add(new JdbcPatternLadderStore.FastItem(
                                0, puzzleId, step.low(), step.high(), null));
                    });
        }
        Map<String, JdbcPatternLadderStore.PatternCategory> others =
                new LinkedHashMap<>(store.patternCategories());
        others.remove(patternKey);
        List<JdbcPatternLadderStore.FastItem> combinations = new ArrayList<>();
        Set<String> wanted = new HashSet<>(others.keySet());
        for (RatingRange range : combinationRanges()) {
            if (wanted.isEmpty()) {
                break;
            }
            Map<String, List<Long>> candidates = store.combinationsInRange(
                    accountId, categoryId, range.low(), range.high(), wanted, chosen, random);
            // A Puzzle can carry several wanted Themes but fills only one, so each Theme takes
            // its first candidate not used yet, in random Theme order.
            List<String> themes = new ArrayList<>(candidates.keySet());
            Collections.shuffle(themes, Random.from(random));
            for (String theme : themes) {
                for (Long puzzleId : candidates.get(theme)) {
                    if (chosen.add(puzzleId)) {
                        wanted.remove(theme);
                        int combined = others.get(theme).categoryId();
                        combinations.add(new JdbcPatternLadderStore.FastItem(
                                0, puzzleId, null, null, combined));
                        break;
                    }
                }
            }
        }
        Collections.shuffle(combinations, Random.from(random));
        items.addAll(combinations);
        if (items.isEmpty()) {
            return Optional.empty();
        }
        long id = store.createFast(accountId, categoryId, items, clock.millis());
        return Optional.of(new FastLadder(id, items.size()));
    }

    @Override
    public FastNext nextFast(String accountId, long fastLadderId) {
        requireAccount(accountId);
        Map<Integer, PatternLadders.Pattern> patterns = null;
        while (true) {
            JdbcPatternLadderStore.FastPresent present =
                    store.presentNextFast(accountId, fastLadderId, clock.millis()).orElse(null);
            if (present == null) {
                return new FastNext(FastOutcome.NOT_FOUND, null, 0, 0, null);
            }
            JdbcPatternLadderStore.FastItem item = present.item();
            if (item == null) {
                return new FastNext(FastOutcome.COMPLETE, null, 0, present.total(), null);
            }
            Optional<PuzzleTraining.Attempt> attempt =
                    training.startInCategory(accountId, item.puzzleId());
            if (attempt.isEmpty()) {
                // A Puzzle removed since the draw is skipped; it was marked presented.
                continue;
            }
            FastStep step;
            if (item.combinedCategoryId() == null) {
                step = new FastStep(new RatingRange(item.ratingLow(), item.ratingHigh()), null);
            } else {
                if (patterns == null) {
                    patterns = new HashMap<>();
                    for (JdbcPatternLadderStore.PatternCategory category
                            : store.patternCategories().values()) {
                        patterns.put(category.categoryId(), category.pattern());
                    }
                }
                step = new FastStep(null, patterns.get(item.combinedCategoryId()));
            }
            return new FastNext(
                    FastOutcome.STARTED, attempt.get(), item.position(), present.total(), step);
        }
    }

    /**
     * The combination search ranges: {@link #FAST_COMBINATION_RANGE} first, then 200-point ranges
     * alternately below and above it until every rating is covered.
     */
    static List<RatingRange> combinationRanges() {
        List<RatingRange> ranges = new ArrayList<>();
        int width = FAST_COMBINATION_RANGE.high() - FAST_COMBINATION_RANGE.low();
        ranges.add(FAST_COMBINATION_RANGE);
        int below = FAST_COMBINATION_RANGE.low();
        int above = FAST_COMBINATION_RANGE.high();
        while (below > 0 || above < RatingPackage.FROM_2600.high()) {
            if (below > 0) {
                ranges.add(new RatingRange(Math.max(0, below - width), below));
                below -= width;
            }
            if (above < RatingPackage.FROM_2600.high()) {
                ranges.add(new RatingRange(above, Math.min(RatingPackage.FROM_2600.high(), above + width)));
                above += width;
            }
        }
        return List.copyOf(ranges);
    }

    private Optional<JdbcPatternLadderStore.PatternCategory> category(String patternKey) {
        if (patternKey == null || !PATTERN_KEY.matcher(patternKey).matches()) {
            return Optional.empty();
        }
        return store.pattern(patternKey);
    }

    private static void requireAccount(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("Account ID is invalid");
        }
    }
}
