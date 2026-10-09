package com.mehf.smsgateway;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;

public class UsersListActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private SharedPreferences prefs;
    
    private String currentUserRole = "";
    private String currentUserSchoolId = "NA";
    private String currentUserDocId = "";
    
    private LinearLayout contactsLayout;
    private List<DocumentSnapshot> allUsersCache = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();
        prefs = getSharedPreferences("MEHF_Prefs", Context.MODE_PRIVATE);

        // UI Setup
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#F4F6F9"));
        
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(40, 40, 40, 40);
        scrollView.addView(mainLayout);
        setContentView(scrollView);

        // 1. HEADER LAYOUT (Title + Logout Button)
        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        headerLayout.setPadding(0, 0, 0, 30);

        TextView title = new TextView(this);
        title.setText("💬 Communication Hub");
        title.setTextSize(20f);
        title.setTextColor(Color.parseColor("#1A237E"));
        title.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        headerLayout.addView(title);

        // 2. LOGOUT BUTTON
        Button btnLogout = new Button(this);
        btnLogout.setText("LOGOUT");
        btnLogout.setBackgroundColor(Color.parseColor("#F44336")); // Red Color
        btnLogout.setTextColor(Color.WHITE);
        headerLayout.addView(btnLogout);

        mainLayout.addView(headerLayout);

        // 3. LOGOUT BUTTON CLICK ACTION
        btnLogout.setOnClickListener(v -> {
            mAuth.signOut(); // Firebase se logout
            prefs.edit().putBoolean("is_connect_user", false).apply(); // Session clear karein
            prefs.edit().putBoolean("is_admin_active_session", false).apply();
            
            Toast.makeText(this, "Logged out successfully!", Toast.LENGTH_SHORT).show();
            
            // Wapas Login Screen par bhejein
            Intent intent = new Intent(UsersListActivity.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });

        // Search Box
        EditText searchBox = new EditText(this);
        searchBox.setHint("🔍 Search by Name, Role or Class...");
        searchBox.setBackgroundColor(Color.WHITE);
        searchBox.setPadding(30, 30, 30, 30);
        mainLayout.addView(searchBox);

        contactsLayout = new LinearLayout(this);
        contactsLayout.setOrientation(LinearLayout.VERTICAL);
        contactsLayout.setPadding(0, 30, 0, 0);
        mainLayout.addView(contactsLayout);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterContacts(s.toString().toLowerCase()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        fetchCurrentUserRoleAndLoadContacts();
    }

    private void fetchCurrentUserRoleAndLoadContacts() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            finish();
            return;
        }

        if (user.getEmail() != null) {
            currentUserDocId = user.getEmail().split("@")[0];
        } else {
            currentUserDocId = user.getUid();
        }

        boolean isAdmin = prefs.getBoolean("is_admin_active_session", false);

        if (isAdmin) {
            currentUserRole = "admin";
            currentUserSchoolId = "NA";
            loadContactsFromFirestore();
        } else {
            db.collection("users").document(currentUserDocId).get().addOnSuccessListener(doc -> {
                if (doc.exists()) {
                    currentUserRole = doc.contains("role") ? doc.getString("role") : "student";
                    currentUserSchoolId = doc.contains("schoolId") ? doc.getString("schoolId") : "NA";
                    loadContactsFromFirestore();
                } else {
                    Toast.makeText(this, "Profile Data Error!", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void loadContactsFromFirestore() {
        db.collection("users").get().addOnSuccessListener(queryDocumentSnapshots -> {
            allUsersCache.clear();
            allUsersCache.addAll(queryDocumentSnapshots.getDocuments());
            renderSpecialChats();
            filterContacts(""); 
        });
    }

    private void renderSpecialChats() {
        contactsLayout.removeAllViews();
        
        addContactCard("AI", "🤖 Smart AI Assistant", "Instant Help & Queries", "#fff0f5", "#E91E63");
        addContactCard("group_all", "📢 School Notice Board", "Official Announcements", "#e8f5e9", "#4CAF50");

        if (!currentUserRole.equals("admin") && !currentUserRole.equals("school")) {
            addContactCard(currentUserSchoolId, "🏫 School Office", "Principal / Management", "#fff8e1", "#FF9800");
        }

        if (currentUserRole.equals("student") || currentUserRole.equals("school")) {
            addContactCard("group_student_all", "👨‍🎓 Student Broadcast", "Message to all students", "#e3f2fd", "#2196F3");
        }
        if (currentUserRole.equals("teacher") || currentUserRole.equals("school")) {
            addContactCard("group_teachers", "👨‍🏫 Teacher Broadcast", "Staff Communication", "#f3e5f5", "#9C27B0");
        }
        
        TextView divider = new TextView(this);
        divider.setText("\n📌 Registered Users:");
        divider.setTextSize(16f);
        divider.setTextColor(Color.GRAY);
        contactsLayout.addView(divider);
    }

    private void filterContacts(String query) {
        renderSpecialChats();
        int renderedCount = 0; 

        for (DocumentSnapshot doc : allUsersCache) {
            String id = doc.getId();
            if (id.equals(currentUserDocId)) continue; 

            String name = doc.contains("name") ? doc.getString("name") : 
                         (doc.contains("school_name") ? doc.getString("school_name") : id);
            String role = doc.contains("role") ? doc.getString("role") : "Unknown";
            String className = doc.contains("class") ? doc.getString("class") : "";
            
            // SCHOOL ISOLATION LOGIC
            String targetSchoolId = doc.contains("schoolId") ? doc.getString("schoolId") : "NA";
            if (role.equals("school")) targetSchoolId = id; 

            boolean isSameSchool = false;
            if (currentUserRole.equals("admin") || role.equals("admin")) {
                isSameSchool = true; 
            } else {
                if (currentUserRole.equals("school")) {
                    isSameSchool = targetSchoolId.equals(currentUserDocId); 
                } else {
                    isSameSchool = targetSchoolId.equals(currentUserSchoolId);
                }
            }

            boolean show = false;
            if (isSameSchool) {
                if (currentUserRole.equals("admin")) show = true;
                else if (currentUserRole.equals("school") && (role.equals("teacher") || role.equals("student") || role.equals("driver") || role.equals("admin"))) show = true;
                else if (currentUserRole.equals("teacher") && (role.equals("school") || role.equals("teacher") || role.equals("student") || role.equals("admin"))) show = true;
                else if (currentUserRole.equals("student") && (role.equals("school") || role.equals("teacher") || role.equals("student") || role.equals("admin"))) show = true;
            }

            if (show) {
                String searchString = (name + " " + role + " " + className).toLowerCase();
                if (query.isEmpty() || searchString.contains(query)) {
                    String subtext = "Role: " + role.toUpperCase() + (!className.isEmpty() ? " | Class: " + className : "");
                    addContactCard(id, name, subtext, "#FFFFFF", "#0F2BEB");
                    renderedCount++;

                    if (query.isEmpty() && renderedCount >= 30) {
                        addContactCard("", "🔍 Search to find more...", "Type name to see remaining users", "#F5F5F5", "#9E9E9E");
                        break; 
                    }
                }
            }
        }
    }

    private void addContactCard(String targetId, String title, String subtitle, String bgColor, String stripColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor(bgColor));
        card.setPadding(30, 20, 30, 20);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 10, 0, 10);
        card.setLayoutParams(params);
        card.setElevation(5f);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16f);
        tvTitle.setTextColor(Color.BLACK);
        tvTitle.getPaint().setFakeBoldText(true);
        card.addView(tvTitle);

        if (!subtitle.isEmpty()) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle);
            tvSub.setTextSize(12f);
            tvSub.setTextColor(Color.parseColor("#666666"));
            card.addView(tvSub);
        }

        if(!targetId.isEmpty()) {
            card.setOnClickListener(v -> {
                Intent intent = new Intent(this, ChatActivity.class);
                intent.putExtra("targetUserId", targetId);
                intent.putExtra("targetUserName", title);
                startActivity(intent);
            });
        }
        contactsLayout.addView(card);
    }
}
