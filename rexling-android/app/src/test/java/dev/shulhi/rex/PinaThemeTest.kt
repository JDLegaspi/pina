package dev.shulhi.rex

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinaThemeTest {
    @Test fun systemAppearanceFollowsThePhoneAndExplicitChoicesOverrideIt() {
        assertTrue(Appearance.SYSTEM.isDark(systemDark = true))
        assertFalse(Appearance.SYSTEM.isDark(systemDark = false))
        assertTrue(Appearance.DARK.isDark(systemDark = false))
        assertFalse(Appearance.LIGHT.isDark(systemDark = true))
    }

    @Test fun materialWidgetsInheritTheIosLightPalette() {
        val scheme = PinaColors.Light.materialScheme()
        assertEquals(Color(0xfff2f2f7), scheme.background)
        assertEquals(Color(0xffffffff), scheme.surface)
        assertEquals(Color(0xff000000), scheme.onSurface)
        assertEquals(Color(0xff007aff), scheme.primary)
        assertEquals(Color(0xffff3b30), scheme.error)
        assertEquals(Color(0xffc6c6c8), scheme.outline)
    }

    @Test fun materialWidgetsInheritTheIosDarkPalette() {
        val scheme = PinaColors.Dark.materialScheme()
        assertEquals(Color(0xff000000), scheme.background)
        assertEquals(Color(0xff1c1c1e), scheme.surface)
        assertEquals(Color(0xffffffff), scheme.onSurface)
        assertEquals(Color(0xff0a84ff), scheme.primary)
        assertEquals(Color(0xffff453a), scheme.error)
        assertEquals(Color(0xff38383a), scheme.outline)
    }

    @Test fun secondaryTextKeepsItsTranslucency() {
        assertEquals(.6f, PinaColors.Light.materialScheme().onSurfaceVariant.alpha, .01f)
        assertEquals(.6f, PinaColors.Dark.materialScheme().onSurfaceVariant.alpha, .01f)
    }
}
