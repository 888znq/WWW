package com.example.urlhud;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists every open "account" session - each with its own name and its
 * own serialized pane tree (see PaneManager.serialize()) - plus which one
 * was active, across process death / relaunch. This is the native
 * equivalent of the extension's tabs array (app.js's `tabs`/`activeTabId`,
 * saved via saveTabsToDisk()), ported to a single SharedPreferences-backed
 * JSON blob instead of localStorage.
 *
 * Previously this store only ever held one bare pane-tree string, which is
 * why the multi-account switcher had no persistence to build on - MainActivity
 * now owns the in-memory list of sessions and reads/writes it here as a whole.
 */
public class SessionStore {

    /** One open account session: a stable id, a display name, and its pane tree (null until first persisted). */
    public static class Session {
        public final String id;
        public String name;
        public JSONObject tree;

        public Session(String id, String name, JSONObject tree) {
            this.id = id;
            this.name = name;
            this.tree = tree;
        }
    }

    /** Everything persisted: the ordered list of sessions, and which one was active. */
    public static class State {
        public final List<Session> sessions = new ArrayList<>();
        public String activeId;
    }

    private static final String PREFS_NAME = "urlhud_session";
    private static final String KEY_STATE = "session_state_json";

    private final SharedPreferences prefs;

    public SessionStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns the last saved state, or an empty one (no sessions) if none was saved yet or it failed to parse. */
    public State load() {
        State state = new State();
        String raw = prefs.getString(KEY_STATE, null);
        if (raw == null) return state;
        try {
            JSONObject o = new JSONObject(raw);
            state.activeId = o.isNull("activeId") ? null : o.optString("activeId", null);
            JSONArray arr = o.optJSONArray("sessions");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s == null) continue;
                    String id = s.optString("id", null);
                    if (id == null || id.isEmpty()) continue;
                    String name = s.optString("name", "Session");
                    JSONObject tree = s.optJSONObject("tree");
                    state.sessions.add(new Session(id, name, tree));
                }
            }
        } catch (JSONException ignored) {
            // Corrupt/old-format state - start fresh rather than crash.
        }
        return state;
    }

    public void save(State state) {
        try {
            JSONObject o = new JSONObject();
            o.put("activeId", state.activeId);
            JSONArray arr = new JSONArray();
            for (Session s : state.sessions) {
                JSONObject so = new JSONObject();
                so.put("id", s.id);
                so.put("name", s.name);
                if (s.tree != null) so.put("tree", s.tree);
                arr.put(so);
            }
            o.put("sessions", arr);
            prefs.edit().putString(KEY_STATE, o.toString()).apply();
        } catch (JSONException ignored) {}
    }

    public void clear() {
        prefs.edit().remove(KEY_STATE).apply();
    }
}
