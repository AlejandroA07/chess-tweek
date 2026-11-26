package com.chesstweek.training.internal.performance;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


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

    @Autowired
    public PatternLadderService(JdbcPatternLadderStore store, PuzzleTrainingService training) {
        this(store, training, Clock.systemUTC());
    }

    PatternLadderService(JdbcPatternLadderStore store, PuzzleTrainingService training, Clock clock) {
        this.store = store;
        this.training = training;
        this.clock = clock;
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
