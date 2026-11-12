package cue.edu.co.yousongbackend.establecimiento;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The slug is part of every printed QR, so it must be a clean, predictable address segment. */
class SlugTest {

    @Test
    void lowercasesAndJoinsWordsWithHyphens() {
        assertThat(Slug.de("Taberna El Rincon")).isEqualTo("taberna-el-rincon");
    }

    @Test
    void removesAccentsAndTheLetterEnye() {
        assertThat(Slug.de("Taberna Él Rincón")).isEqualTo("taberna-el-rincon");
        assertThat(Slug.de("Ñandú")).isEqualTo("nandu");
    }

    @Test
    void dropsSymbolsAndCollapsesSeparators() {
        assertThat(Slug.de("  ¡Bar #1!!  ")).isEqualTo("bar-1");
        assertThat(Slug.de("a---b___c")).isEqualTo("a-b-c");
    }

    @Test
    void neverStartsOrEndsWithAHyphen() {
        assertThat(Slug.de("-- Bar --")).isEqualTo("bar");
    }

    @Test
    void fallsBackToAGenericNameWhenNothingUsableRemains() {
        assertThat(Slug.de("")).isEqualTo("local");
        assertThat(Slug.de("!!! ???")).isEqualTo("local");
    }

    @Test
    void staysWithinTheLengthLeftForADisambiguationSuffix() {
        String slug = Slug.de("x".repeat(300));
        assertThat(slug).hasSizeLessThanOrEqualTo(Slug.MAX_BASE_LENGTH);
        assertThat(Slug.MAX_BASE_LENGTH).isLessThanOrEqualTo(80 - 7); // column is varchar(80); room for "-" + suffix
    }

    @Test
    void doesNotLeaveAHyphenWhenTruncating() {
        String slug = Slug.de(("ab ").repeat(60));
        assertThat(slug).doesNotEndWith("-").matches("[a-z0-9-]+");
    }

    @Test
    void producesOnlyWhatTheDatabaseConstraintAllows() {
        for (String name : new String[] {"Café Ñ Mundo", "100% Música", "  ", "中文 Bar", "BAR_1"}) {
            assertThat(Slug.de(name)).matches("[a-z0-9-]+");
        }
    }
}
