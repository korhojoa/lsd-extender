/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import de.vw.mib.datapool.I18nDatapool;

/** Owned action text where the retained native tables have no suitable caption. */
final class ProjectUpdateActionText {
    private static final String[][] TEXT = {
        {"ar", "\u062a\u062d\u0642\u0642", "\u062a\u062b\u0628\u064a\u062a"},
        {"bg", "\u041f\u0440\u043e\u0432\u0435\u0440\u043a\u0430", "\u0418\u043d\u0441\u0442\u0430\u043b\u0438\u0440\u0430\u043d\u0435"},
        {"bs", "Provjeri", "Instaliraj"},
        {"cs", "Zkontrolovat", "Instalovat"},
        {"da", "Kontroll\u00e9r", "Installer"},
        {"de", "Pr\u00fcfen", "Installieren"},
        {"el", "\u0388\u03bb\u03b5\u03b3\u03c7\u03bf\u03c2", "\u0395\u03b3\u03ba\u03b1\u03c4\u03ac\u03c3\u03c4\u03b1\u03c3\u03b7"},
        {"en", "Check", "Install"},
        {"es", "Comprobar", "Instalar"},
        {"et", "Kontrolli", "Paigalda"},
        {"fi", "Tarkista", "Asenna"},
        {"fr", "V\u00e9rifier", "Installer"},
        {"hr", "Provjeri", "Instaliraj"},
        {"hu", "Ellen\u0151rz\u00e9s", "Telep\u00edt\u00e9s"},
        {"it", "Verifica", "Installa"},
        {"lt", "Tikrinti", "\u012ediegti"},
        {"lv", "P\u0101rbaud\u012bt", "Instal\u0113t"},
        {"nl", "Controleren", "Installeren"},
        {"no", "Kontroller", "Installer"},
        {"pl", "Sprawd\u017a", "Zainstaluj"},
        {"pt", "Verificar", "Instalar"},
        {"ro", "Verific\u0103", "Instaleaz\u0103"},
        {"ru", "\u041f\u0440\u043e\u0432\u0435\u0440\u0438\u0442\u044c", "\u0423\u0441\u0442\u0430\u043d\u043e\u0432\u0438\u0442\u044c"},
        {"sk", "Skontrolova\u0165", "In\u0161talova\u0165"},
        {"sl", "Preveri", "Namesti"},
        {"sr", "Proveri", "Instaliraj"},
        {"sv", "Kontrollera", "Installera"},
        {"tr", "Kontrol et", "Y\u00fckle"},
        {"uk", "\u041f\u0435\u0440\u0435\u0432\u0456\u0440\u0438\u0442\u0438", "\u0412\u0441\u0442\u0430\u043d\u043e\u0432\u0438\u0442\u0438"}
    };
    private static final String[] LOCALES = {"ar_SA", "bg_BG", "bs_BA", "cs_CZ", "da_DK", "de_DE", "el_GR", "en_GB", "en_US", "es_ES", "es_MX", "et_EE", "fi_FI", "fr_FR", "hr_HR", "hu_HU", "it_IT", "lt_LT", "lv_LV", "nl_NL", "no_NO", "pl_PL", "pt_BR", "pt_PT", "ro_RO", "ru_RU", "sk_SK", "sl_SI", "sr_RS", "sv_SE", "tr_TR", "uk_UA"};
    private final I18nDatapool nativeText;
    private final int original;
    private boolean install;
    private String unsupported;
    ProjectUpdateActionText(I18nDatapool nativeText, int original) {
        this.nativeText = nativeText; this.original = original;
    }
    void staged(boolean value) { install = value; }
    String value() {
        de.vw.mib.configuration.ConfigurationManager configuration = de.vw.mib.desktop.internal.common.ServiceManager.configurationManager;
        String locale = configuration == null ? null : configuration.getCurrentGuiLanguage();
        String result = translated(locale, install);
        if (result != null) { return result; }
        String identity = String.valueOf(locale);
        if (!identity.equals(unsupported)) {
            unsupported = identity;
            org.korho.lsdextender.Slog.write(" homeui: updater caption unavailable for GUI language " + identity
                + "; retaining native Start wall_ms=" + System.currentTimeMillis());
        }
        return nativeText.getString(original);
    }
    static String translated(String locale, boolean install) {
        if (locale == null) { return null; }
        // Native locale identities are exact. Do not label an unknown locale as English.

        boolean supported = false;
        for (int i = 0; i < LOCALES.length; i++) { if (LOCALES[i].equals(locale)) { supported = true; break; } }
        if (!supported) { return null; }
        String language = locale.substring(0, 2);
        for (int i = 0; i < TEXT.length; i++) { if (TEXT[i][0].equals(language)) { return TEXT[i][install ? 2 : 1]; } }
        return null;
    }
}
