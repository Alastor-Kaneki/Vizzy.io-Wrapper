package dev.alastorkaneki.vizzywrapper;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Crash-safe local autosave plus SAF import/export for native project files. */
public final class ProjectStore {
    private static final String AUTOSAVE = "native-project-autosave.json";

    private ProjectStore() {}

    public static void autosave(Context context, ProjectModel project) throws Exception {
        File target = new File(context.getFilesDir(), AUTOSAVE);
        File temp = new File(context.getFilesDir(), AUTOSAVE + ".tmp");
        byte[] bytes = project.toJson().toString(2).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace autosave.");
        if (!temp.renameTo(target)) throw new IOException("Could not commit autosave.");
    }

    public static ProjectModel loadAutosave(Context context) {
        File target = new File(context.getFilesDir(), AUTOSAVE);
        if (!target.isFile()) return null;
        try (InputStream input = new FileInputStream(target)) {
            return read(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void write(Context context, Uri destination, ProjectModel project) throws Exception {
        try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("The selected destination could not be opened.");
            output.write(project.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    public static ProjectModel read(Context context, Uri source) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("The selected project could not be opened.");
            return read(input);
        }
    }

    private static ProjectModel read(InputStream input) throws Exception {
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) json.append(buffer, 0, count);
        }
        return ProjectModel.fromJson(new JSONObject(json.toString()));
    }
}
