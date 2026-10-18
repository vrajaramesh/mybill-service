package com.example.mybill.boutique.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Caption / permalink helpers of the Instagram sync (no Spring). */
class MarketingServiceTextTest {

    @Test
    void designCodeIn_prefersCodeLabel_elseFirstMatch() {
        assertThat(MarketingService.designCodeIn("Loving KUR-402 but Code: KUR-401 is here")).isEqualTo("KUR-401");
        assertThat(MarketingService.designCodeIn("code - kur-401")).isEqualTo("KUR-401");
        assertThat(MarketingService.designCodeIn("New KUR-402 and LEH-7")).isEqualTo("KUR-402");
        assertThat(MarketingService.designCodeIn("lowercase kur-402 only")).isNull();   // [A-Z]{3}-\d+ outside "Code:"
        assertThat(MarketingService.designCodeIn("no code")).isNull();
        assertThat(MarketingService.designCodeIn(null)).isNull();
    }

    @Test
    void captionLabel_first60CodePoints_whitespaceCollapsed() {
        assertThat(MarketingService.captionLabel("  Short\n\ncaption  ")).isEqualTo("Short caption");
        String label = MarketingService.captionLabel("✨".repeat(10) + "x".repeat(80));
        assertThat(label.codePointCount(0, label.length())).isEqualTo(60);
        assertThat(label).startsWith("✨✨");
    }

    @Test
    void normalisePermalink() {
        assertThat(MarketingService.normalisePermalink("https://www.instagram.com/p/AbC123/?igsh=x#frag"))
            .isEqualTo("https://instagram.com/p/AbC123");
        assertThat(MarketingService.normalisePermalink("http://INSTAGRAM.com/reel/XyZ/"))
            .isEqualTo("https://instagram.com/reel/XyZ");
        assertThat(MarketingService.normalisePermalink("  ")).isNull();
    }
}
