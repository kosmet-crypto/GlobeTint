package com.kosmet.travelmap;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Texts shown by the native part (update dialogs, toasts) in the phone's language: Serbian, Norwegian or English. */
final class Strings {

    private static final Map<String, String[]> TEXT = new HashMap<>();

    static {
        // key: { English, Serbian (Cyrillic), Norwegian }
        put("checking", "Checking for updates…", "Провера ажурирања…", "Ser etter oppdateringer…");
        put("latest", "You have the latest version", "Имаш најновију верзију", "Du har nyeste versjon");
        put("offline", "Could not check. Are you online?", "Провера није успела. Има ли интернета?", "Kunne ikke sjekke. Er du på nett?");
        put("pageTitle", "Update downloaded", "Ажурирање је преузето", "Oppdatering lastet ned");
        put("pageText", "A new version of GlobeTint is ready. Restart now to use it? Your data stays in place.",
                "Нова верзија GlobeTint-а је спремна. Поново покренути сада? Подаци остају.",
                "En ny versjon av GlobeTint er klar. Starte på nytt nå? Dataene dine blir værende.");
        put("restart", "Restart", "Покрени", "Start på nytt");
        put("later", "Later", "Касније", "Senere");
        put("apkTitle", "Update available", "Ново ажурирање", "Oppdatering tilgjengelig");
        put("apkText", "GlobeTint %s is ready. Install it now? Your data stays in place.",
                "GlobeTint %s је спреман. Инсталирати сада? Подаци остају.",
                "GlobeTint %s er klar. Installere nå? Dataene dine blir værende.");
        put("update", "Update", "Ажурирај", "Oppdater");
        put("allowInstall", "Allow GlobeTint to install updates, then go back",
                "Дозволи GlobeTint-у да инсталира ажурирања, па се врати",
                "Tillat GlobeTint å installere oppdateringer, og gå tilbake");
        put("downloading", "Downloading update…", "Преузимање ажурирања…", "Laster ned oppdatering…");
        put("downloadFailed", "Could not download the update. Are you online?",
                "Ажурирање није преузето. Има ли интернета?", "Kunne ikke laste ned oppdateringen. Er du på nett?");
        put("installer", "Opening the installer…", "Отварам инсталацију…", "Åpner installasjonen…");
        put("installerFailed", "Could not open the installer", "Инсталација није отворена", "Kunne ikke åpne installasjonen");
        put("installed", "Update installed", "Ажурирање је инсталирано", "Oppdatering installert");
        put("signature", "This update cannot replace the installed app (different signature)",
                "Ово ажурирање не може да замени инсталирану апликацију (други потпис)",
                "Denne oppdateringen kan ikke erstatte appen (annen signatur)");
        put("installFailed", "Update failed. Try again later.", "Ажурирање није успело. Покушај касније.",
                "Oppdateringen mislyktes. Prøv igjen senere.");
        put("backupSaved", "Backup saved", "Бекап је сачуван", "Sikkerhetskopi lagret");
        put("imageSaved", "Image saved", "Слика је сачувана", "Bildet er lagret");
        put("saveFailed", "Could not save the file", "Фајл није сачуван", "Kunne ikke lagre filen");
        put("noSaver", "No app available to save files", "Нема апликације за чување фајлова", "Ingen app for å lagre filer");
        put("shareFailed", "Could not share the image", "Слика није подељена", "Kunne ikke dele bildet");
    }

    private Strings() {
    }

    private static void put(String key, String en, String sr, String nb) {
        TEXT.put(key, new String[]{en, sr, nb});
    }

    static String get(Context context, String key) {
        String[] t = TEXT.get(key);
        if (t == null) return key;
        String lang = Locale.getDefault().getLanguage();
        if ("sr".equals(lang)) return t[1];
        if ("nb".equals(lang) || "no".equals(lang) || "nn".equals(lang)) return t[2];
        return t[0];
    }
}
