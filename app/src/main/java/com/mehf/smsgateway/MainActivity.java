package com.mehf.smsgateway;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private String deviceId;
    private LinearLayout mainLayout;
    private String loggedInSchool = "";
    private boolean isAdminMode = false;
    private SharedPreferences sharedPreferences;

    private TextView remainingSmsTxt, sentSmsTxt, pendingSmsTxt, failedSmsTxt;

    // Daily Limit & Rollover Variables
    private long currentDailyUsed = 0;
    private long currentPerdayLimit = 0;
    private boolean isUnlimitedPlan = false;
    private long currentTotalLimit = 0;
    private long currentTotalUsed = 0;
    private String currentActiveDate = "";
    private boolean limitToastShown = false;

    // ==================== STATIC SMS RECEIVER ====================
    public static class SmsResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String docId = intent.getStringExtra("docId");
            String schoolId = intent.getStringExtra("schoolId");
            boolean isAdmin = intent.getBooleanExtra("isAdmin", false);
            
            if (docId == null) return;
            
            FirebaseFirestore database = FirebaseFirestore.getInstance();
            
            if (getResultCode() == Activity.RESULT_OK) {
                database.collection("sms_logs").document(docId).update("status", "sent");
                if (!isAdmin && schoolId != null && !schoolId.isEmpty()) {
                    database.collection("users").document(schoolId).update(
                        "used_sms", FieldValue.increment(1),
                        "daily_used", FieldValue.increment(1)
                    );
                }
            } else {
                database.collection("sms_logs").document(docId).update("status", "failed");
            }
        }
    }

    @SuppressLint("HardwareIds")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            FirebaseApp.initializeApp(this);
            db = FirebaseFirestore.getInstance();
            deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            sharedPreferences = getSharedPreferences("MEHF_Prefs", Context.MODE_PRIVATE);

            ScrollView scrollView = new ScrollView(this);
            scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrollView.setBackgroundColor(Color.parseColor("#F4F6F9"));

            mainLayout = new LinearLayout(this);
            mainLayout.setOrientation(LinearLayout.VERTICAL);
            mainLayout.setLayoutParams(new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
            
            scrollView.addView(mainLayout);
            setContentView(scrollView);

            checkPermissionsAndStart();

        } catch (Exception e) {
            TextView errorText = new TextView(this);
            errorText.setText("Startup Error: \n\n" + e.getMessage());
            errorText.setTextColor(Color.RED);
            setContentView(errorText);
        }
    }

    private void checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE}, 101);
        } else {
            startBackgroundService();
            checkAutoLogin();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startBackgroundService();
        checkAutoLogin(); 
    }

    // ==================== [NEW] BACKGROUND & AUTO-LOGIN ====================
    private void startBackgroundService() {
        Intent serviceIntent = new Intent(this, SmsBackgroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void checkAutoLogin() {
        boolean isMasterAdmin = sharedPreferences.getBoolean("is_admin_active_session", false);
        String lastSchool = sharedPreferences.getString("last_active_school", "");

        if (isMasterAdmin) {
            isAdminMode = true;
            showAdminDashboard();
        } else if (!lastSchool.isEmpty()) {
            String savedPass = sharedPreferences.getString("pass_" + lastSchool, "");
            handleSchoolLogin(lastSchool, savedPass, false, -1); // Auto login bypasses UI
        } else {
            showLoginScreen();
        }
    }

    // ==================== 1. LOGIN SCREEN ====================
    private void showLoginScreen() {
        mainLayout.removeAllViews();
        mainLayout.setPadding(60, 80, 60, 60);

        TextView title = new TextView(this);
        title.setText("MEHF School Login");
        title.setTextSize(26f);
        title.setTextColor(Color.parseColor("#1A237E"));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 40);
        mainLayout.addView(title);

        RadioGroup roleGroup = new RadioGroup(this);
        roleGroup.setOrientation(LinearLayout.HORIZONTAL);
        roleGroup.setGravity(Gravity.CENTER);
        RadioButton rdoSchool = new RadioButton(this); rdoSchool.setText("School"); rdoSchool.setId(View.generateViewId());
        RadioButton rdoAdmin = new RadioButton(this); rdoAdmin.setText("Admin"); rdoAdmin.setId(View.generateViewId());
        roleGroup.addView(rdoSchool); roleGroup.addView(rdoAdmin);
        mainLayout.addView(roleGroup);
        rdoSchool.setChecked(true);

        EditText usernameInput = new EditText(this); usernameInput.setHint("Username (School ID)");
        mainLayout.addView(usernameInput);

        EditText passwordInput = new EditText(this); passwordInput.setHint("Password");
        mainLayout.addView(passwordInput);

        // 🚨 [NEW] SIM SELECTION OPTION 🚨
        TextView simLabel = new TextView(this); simLabel.setText("\nSelect SIM for Sending SMS:");
        mainLayout.addView(simLabel);
        
        Spinner simSpinner = new Spinner(this);
        List<Integer> subIds = new ArrayList<>();
        List<String> simNames = new ArrayList<>();
        simNames.add("Default SIM (Auto)");
        subIds.add(-1);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            SubscriptionManager subManager = SubscriptionManager.from(this);
            List<SubscriptionInfo> simInfoList = subManager.getActiveSubscriptionInfoList();
            if (simInfoList != null) {
                for (int i = 0; i < simInfoList.size(); i++) {
                    SubscriptionInfo info = simInfoList.get(i);
                    simNames.add("SIM " + (i + 1) + " (" + info.getCarrierName() + ")");
                    subIds.add(info.getSubscriptionId());
                }
            }
        }
        ArrayAdapter<String> simAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, simNames);
        simSpinner.setAdapter(simAdapter);
        mainLayout.addView(simSpinner);

        Button loginBtn = new Button(this);
        loginBtn.setText("LOGIN");
        loginBtn.setBackgroundColor(Color.parseColor("#2E7D32"));
        loginBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, 40, 0, 0);
        loginBtn.setLayoutParams(btnParams);
        mainLayout.addView(loginBtn);

        Button recoverBtn = new Button(this);
        recoverBtn.setText("Recover Account (Admin / School)");
        recoverBtn.setBackgroundColor(Color.TRANSPARENT);
        recoverBtn.setTextColor(Color.parseColor("#757575"));
        mainLayout.addView(recoverBtn);

        // Hide SIM option if Admin is selected
        roleGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int visibility = (checkedId == rdoAdmin.getId()) ? View.GONE : View.VISIBLE;
            simLabel.setVisibility(visibility);
            simSpinner.setVisibility(visibility);
        });

        loginBtn.setOnClickListener(v -> {
            String user = usernameInput.getText().toString().trim();
            String pass = passwordInput.getText().toString().trim();
            int selectedSimId = subIds.get(simSpinner.getSelectedItemPosition());

            if (roleGroup.getCheckedRadioButtonId() == rdoAdmin.getId()) {
                handleAdminLogin();
            } else {
                handleSchoolLogin(user, pass, true, selectedSimId);
            }
        });

        recoverBtn.setOnClickListener(v -> showMasterRecoveryDialog());
    }

    private void handleAdminLogin() {
        Toast.makeText(this, "Verifying Admin Device...", Toast.LENGTH_SHORT).show();
        db.collection("system_settings").document("admin_data").get().addOnSuccessListener(doc -> {
            if (doc.exists() && doc.contains("admin_device_id") && !doc.getString("admin_device_id").isEmpty()) {
                String savedId = doc.getString("admin_device_id");
                if (deviceId.equals(savedId)) {
                    sharedPreferences.edit().putBoolean("is_admin_device", true).apply();
                    sharedPreferences.edit().putBoolean("is_admin_active_session", true).apply(); // NEW AUTO LOGIN FLAG
                    isAdminMode = true;
                    showAdminDashboard();
                } else {
                    showAlert("Notice", "Admin is already logged in on another device. Please use Admin Recovery.");
                }
            } else {
                db.collection("system_settings").document("admin_data").update("admin_device_id", deviceId);
                sharedPreferences.edit().putBoolean("is_admin_device", true).apply();
                sharedPreferences.edit().putBoolean("is_admin_active_session", true).apply(); // NEW AUTO LOGIN FLAG
                isAdminMode = true;
                showAdminDashboard();
            }
        });
    }

    private void handleSchoolLogin(String username, String password, boolean checkDeviceLock, int simSubId) {
        if(username.isEmpty() || password.isEmpty()){
             Toast.makeText(this, "Username aur Password bharein!", Toast.LENGTH_SHORT).show();
             return;
        }
        db.collection("users").document(username).get().addOnSuccessListener(doc -> {
            if (doc.exists()) {
                if (password.equals(doc.getString("password"))) {
                    if (!doc.contains("recovery_pin") || doc.getString("recovery_pin") == null || doc.getString("recovery_pin").isEmpty()) {
                        showFirstTimeSetupDialog(username, doc);
                        return;
                    }
                    if (checkDeviceLock && doc.contains("school_device_id") && !doc.getString("school_device_id").isEmpty()) {
                        String activeDeviceId = doc.getString("school_device_id");
                        if (!deviceId.equals(activeDeviceId)) {
                            showAlert("Device Locked", "This school is already active on another device.");
                            return;
                        }
                    }
                    db.collection("users").document(username).update("school_device_id", deviceId);
                    loggedInSchool = username;
                    isAdminMode = false;
                    
                    // 🚨 [NEW] AUTO LOGIN & SIM SAVING LOGIC 🚨
                    sharedPreferences.edit().putString("pass_" + username, password).apply();
                    sharedPreferences.edit().putString("last_active_school", username).apply();
                    sharedPreferences.edit().putBoolean("is_admin_active_session", false).apply();
                    if (simSubId != -1) {
                        sharedPreferences.edit().putInt("sim_" + username, simSubId).apply(); // Save selected SIM for this school
                    }

                    String linked = sharedPreferences.getString("linked_list", "");
                    if (!linked.contains(username)) {
                        linked = linked.isEmpty() ? username : linked + "," + username;
                        sharedPreferences.edit().putString("linked_list", linked).apply();
                    }
                    showSchoolDashboard(doc);
                } else {
                    Toast.makeText(this, "Galat Password!", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Username nahi mila!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showFirstTimeSetupDialog(String username, DocumentSnapshot doc) {
        // [Existing Setup Dialog Code Unchanged]
        ScrollView dialogScroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        dialogScroll.addView(layout);

        TextView alertTitle = new TextView(this); alertTitle.setText("🔒 First Time Account Setup");
        alertTitle.setTextSize(18f); alertTitle.setTextColor(Color.parseColor("#1A237E")); alertTitle.setPadding(0, 0, 0, 20);
        layout.addView(alertTitle);

        EditText schoolNameInput = new EditText(this); schoolNameInput.setHint("Enter Your School/Coaching Name");
        if(doc.contains("school_name") && doc.getString("school_name") != null) schoolNameInput.setText(doc.getString("school_name"));
        layout.addView(schoolNameInput);

        EditText pinInput = new EditText(this); pinInput.setHint("Create 4-Digit Security Recovery PIN");
        layout.addView(pinInput);

        Button saveBtn = new Button(this); saveBtn.setText("ACTIVATE ACCOUNT & START");
        saveBtn.setBackgroundColor(Color.parseColor("#2E7D32")); saveBtn.setTextColor(Color.WHITE);
        layout.addView(saveBtn);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogScroll).setCancelable(false).show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        saveBtn.setOnClickListener(v -> {
            String name = schoolNameInput.getText().toString().trim();
            String pin = pinInput.getText().toString().trim();
            if (name.isEmpty() || pin.length() < 4) {
                Toast.makeText(this, "School Name bharein aur 4-digit PIN banayein!", Toast.LENGTH_SHORT).show();
                return;
            }
            Map<String, Object> setupData = new HashMap<>();
            setupData.put("school_name", name); setupData.put("recovery_pin", pin); setupData.put("school_device_id", deviceId);
            db.collection("users").document(username).update(setupData).addOnSuccessListener(aVoid -> {
                Toast.makeText(this, "Account Activated Successfully!", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                handleSchoolLogin(username, doc.getString("password"), true, -1);
            });
        });
    }

    private void showMasterRecoveryDialog() {
        // [Existing Recovery Dialog Code Unchanged]
        ScrollView dialogScroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        dialogScroll.addView(layout);

        RadioGroup recGroup = new RadioGroup(this); recGroup.setOrientation(LinearLayout.HORIZONTAL); recGroup.setGravity(Gravity.CENTER);
        RadioButton rdoRecSchool = new RadioButton(this); rdoRecSchool.setText("School Recovery"); rdoRecSchool.setId(View.generateViewId());
        RadioButton rdoRecAdmin = new RadioButton(this); rdoRecAdmin.setText("Admin Recovery"); rdoRecAdmin.setId(View.generateViewId());
        recGroup.addView(rdoRecSchool); recGroup.addView(rdoRecAdmin); layout.addView(recGroup); rdoRecSchool.setChecked(true);

        EditText schoolUserInput = new EditText(this); schoolUserInput.setHint("Enter School Username"); layout.addView(schoolUserInput);
        EditText pinInput = new EditText(this); pinInput.setHint("Enter Secret Recovery PIN"); layout.addView(pinInput);

        Button submitBtn = new Button(this); submitBtn.setText("VERIFY & RESET DEVICE LOCK");
        submitBtn.setBackgroundColor(Color.parseColor("#E65100")); submitBtn.setTextColor(Color.WHITE); layout.addView(submitBtn);

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Account Recovery System").setView(dialogScroll).show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        rdoRecAdmin.setOnClickListener(v -> schoolUserInput.setVisibility(View.GONE));
        rdoRecSchool.setOnClickListener(v -> schoolUserInput.setVisibility(View.VISIBLE));

        submitBtn.setOnClickListener(v -> {
            String inputPin = pinInput.getText().toString().trim();
            if(inputPin.isEmpty()) return;

            if (recGroup.getCheckedRadioButtonId() == rdoRecAdmin.getId()) {
                db.collection("system_settings").document("admin_data").get().addOnSuccessListener(doc -> {
                    String savedPin = doc.contains("recovery_pin") ? doc.getString("recovery_pin") : "1999"; 
                    if (inputPin.equals(savedPin)) {
                        db.collection("system_settings").document("admin_data").update("admin_device_id", deviceId);
                        sharedPreferences.edit().putBoolean("is_admin_device", true).apply();
                        Toast.makeText(this, "Admin Account Recovered!", Toast.LENGTH_LONG).show();
                        dialog.dismiss(); 
                        sharedPreferences.edit().putBoolean("is_admin_active_session", true).apply();
                        isAdminMode = true; showAdminDashboard();
                    } else { Toast.makeText(this, "Galat Admin PIN!", Toast.LENGTH_SHORT).show(); }
                });
            } else {
                String schoolUser = schoolUserInput.getText().toString().trim();
                if(schoolUser.isEmpty()) return;
                db.collection("users").document(schoolUser).get().addOnSuccessListener(doc -> {
                    if(doc.exists()) {
                        if (!doc.contains("recovery_pin") || doc.getString("recovery_pin").isEmpty()) {
                            Toast.makeText(this, "Is school ka recovery PIN set nahi hai.", Toast.LENGTH_LONG).show(); return;
                        }
                        if (inputPin.equals(doc.getString("recovery_pin"))) {
                            db.collection("users").document(schoolUser).update("school_device_id", deviceId);
                            Toast.makeText(this, "Lock Reset Successful!", Toast.LENGTH_LONG).show();
                            dialog.dismiss(); handleSchoolLogin(schoolUser, doc.getString("password"), false, -1);
                        } else { Toast.makeText(this, "Galat PIN!", Toast.LENGTH_SHORT).show(); }
                    }
                });
            }
        });
    }

    // ==================== 3. ADMIN DASHBOARD ====================
    private void showAdminDashboard() {
        mainLayout.removeAllViews();
        mainLayout.setPadding(30, 30, 30, 30);

        TextView header = new TextView(this); header.setText("MEHF ADMIN PANEL\n(SMS Status: Unlimited)");
        header.setTextSize(20f); header.setTextColor(Color.WHITE); header.setBackgroundColor(Color.parseColor("#1A237E"));
        header.setPadding(30, 40, 30, 40); header.setGravity(Gravity.CENTER); mainLayout.addView(header);

        LinearLayout row1 = new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        
        remainingSmsTxt = createBox("Bache Hue SMS", "#43A047"); sentSmsTxt = createBox("Total Sent SMS", "#1E88E5");
        pendingSmsTxt = createBox("Pending SMS", "#FDD835"); failedSmsTxt = createBox("Failed SMS", "#E53935");
        remainingSmsTxt.setText("Bache Hue SMS\n\nUnlimited");

        row1.addView(remainingSmsTxt); row1.addView(sentSmsTxt); row2.addView(pendingSmsTxt); row2.addView(failedSmsTxt);
        mainLayout.addView(row1); mainLayout.addView(row2);

        LinearLayout.LayoutParams fullWidthParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fullWidthParams.setMargins(15, 15, 15, 15);

        TextView manageSchoolBox = createBox("⚙️ Manage School Plan", "#6A1B9A"); manageSchoolBox.setLayoutParams(fullWidthParams); mainLayout.addView(manageSchoolBox);
        TextView linkSchoolBox = createBox("🔗 Link Multiple School / Switch Account", "#E65100"); linkSchoolBox.setLayoutParams(fullWidthParams); mainLayout.addView(linkSchoolBox);
        
        Button logoutBtn = new Button(this); logoutBtn.setText("LOGOUT ADMIN SESSION"); logoutBtn.setBackgroundColor(Color.parseColor("#D32F2F"));
        logoutBtn.setTextColor(Color.WHITE); logoutBtn.setLayoutParams(fullWidthParams); mainLayout.addView(logoutBtn);

        loadAdminSystemLogs();

        loggedInSchool = "admin"; 
        startAutoSmsSender();

        sentSmsTxt.setOnClickListener(v -> fetchAndShowList("sent", "Sent SMS History"));
        pendingSmsTxt.setOnClickListener(v -> fetchAndShowList("pending", "Pending SMS"));
        failedSmsTxt.setOnClickListener(v -> fetchAndShowList("failed", "Failed SMS"));

        manageSchoolBox.setOnClickListener(v -> showManageSchoolDialog());
        linkSchoolBox.setOnClickListener(v -> showLinkMultipleSchoolDialog());
        
        logoutBtn.setOnClickListener(v -> {
            db.collection("system_settings").document("admin_data").update("admin_device_id", "");
            sharedPreferences.edit().putBoolean("is_admin_device", false).apply();
            sharedPreferences.edit().putBoolean("is_admin_active_session", false).apply(); // CLEAR AUTO LOGIN
            isAdminMode = false; 
            loggedInSchool = ""; 
            showLoginScreen();
        });
    }

    private void loadAdminSystemLogs() {
        db.collection("sms_logs").addSnapshotListener((snaps, e) -> {
            if (snaps == null) return;
            int sent = 0, pending = 0, failed = 0;
            for (QueryDocumentSnapshot d : snaps) {
                String stat = d.getString("status");
                if ("sent".equals(stat)) sent++;
                else if ("pending".equals(stat)) pending++;
                else if ("failed".equals(stat)) failed++;
            }
            sentSmsTxt.setText("Total Sent SMS\n\n" + sent); pendingSmsTxt.setText("Pending SMS\n\n" + pending); failedSmsTxt.setText("Failed SMS\n\n" + failed);
        });
    }

    private void showManageSchoolDialog() {
        // [Existing Manage Dialog Unchanged]
        Toast.makeText(this, "Loading users...", Toast.LENGTH_SHORT).show();
        db.collection("users").get().addOnSuccessListener(docs -> {
            ArrayList<String> schoolList = new ArrayList<>();
            for (DocumentSnapshot d : docs) { schoolList.add(d.getId()); }

            ScrollView dialogScroll = new ScrollView(this);
            LinearLayout dialogLayout = new LinearLayout(this); dialogLayout.setOrientation(LinearLayout.VERTICAL); dialogLayout.setPadding(40, 40, 40, 40);
            dialogScroll.addView(dialogLayout);

            TextView lbl = new TextView(this); lbl.setText("School / User Chunein:"); dialogLayout.addView(lbl);
            Spinner spinner = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, schoolList);
            spinner.setAdapter(adapter); dialogLayout.addView(spinner);

            EditText limitInput = new EditText(this); limitInput.setHint("Perday SMS Limit (Type 'unlimited' or Number)"); dialogLayout.addView(limitInput);
            EditText startInput = new EditText(this); startInput.setHint("Start Date (YYYY-MM-DD)"); dialogLayout.addView(startInput);
            EditText expiryInput = new EditText(this); expiryInput.setHint("Expiry Date (YYYY-MM-DD)"); dialogLayout.addView(expiryInput);

            Button submitBtn = new Button(this); submitBtn.setText("FINISH & SUBMIT PLAN"); submitBtn.setBackgroundColor(Color.parseColor("#6A1B9A"));
            submitBtn.setTextColor(Color.WHITE); dialogLayout.addView(submitBtn);

            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Manage School Plan").setView(dialogScroll).show();

            submitBtn.setOnClickListener(v -> {
                String selectedSchool = spinner.getSelectedItem().toString();
                String limitVal = limitInput.getText().toString().trim();
                String startD = startInput.getText().toString().trim();
                String expD = expiryInput.getText().toString().trim();

                if(limitVal.isEmpty() || startD.isEmpty() || expD.isEmpty()) { Toast.makeText(this, "Details bharein!", Toast.LENGTH_SHORT).show(); return; }

                Map<String, Object> updateData = new HashMap<>();
                updateData.put("perday_sms", limitVal); updateData.put("start_date", startD); updateData.put("expiry_date", expD); updateData.put("activation_date", startD); updateData.put("renew_time", "12:00 AM");
                if(!limitVal.equalsIgnoreCase("unlimited")) { updateData.put("total_sms", Long.parseLong(limitVal) * 30); } else { updateData.put("total_sms", 999999); }
                updateData.put("used_sms", 0); updateData.put("daily_used", 0); 

                db.collection("users").document(selectedSchool).update(updateData).addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Plan updated successfully!", Toast.LENGTH_LONG).show(); dialog.dismiss();
                });
            });
        });
    }

    // ==================== 4. SCHOOL DASHBOARD ====================
    private void showSchoolDashboard(DocumentSnapshot schoolData) {
        mainLayout.removeAllViews();
        mainLayout.setPadding(30, 30, 30, 30);

        TextView header = new TextView(this); header.setText(schoolData.getString("school_name") + "\nSMS Dashboard");
        header.setTextSize(20f); header.setTextColor(Color.WHITE); header.setBackgroundColor(Color.parseColor("#3949AB"));
        header.setPadding(30, 40, 30, 40); header.setGravity(Gravity.CENTER); mainLayout.addView(header);

        LinearLayout row1 = new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        
        remainingSmsTxt = createBox("Bache Hue SMS", "#43A047"); sentSmsTxt = createBox("Total Sent SMS", "#1E88E5");
        pendingSmsTxt = createBox("Pending SMS", "#FDD835"); failedSmsTxt = createBox("Failed SMS", "#E53935");

        row1.addView(remainingSmsTxt); row1.addView(sentSmsTxt); row2.addView(pendingSmsTxt); row2.addView(failedSmsTxt);
        mainLayout.addView(row1); mainLayout.addView(row2);

        LinearLayout.LayoutParams fullWidthParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fullWidthParams.setMargins(15, 15, 15, 15);

        TextView linkSchoolBox = createBox("🔗 Link Multiple School / Switch Account", "#E65100"); linkSchoolBox.setLayoutParams(fullWidthParams); mainLayout.addView(linkSchoolBox);
        Button logoutBtn = new Button(this); logoutBtn.setText("LOGOUT CURRENT SCHOOL"); logoutBtn.setBackgroundColor(Color.parseColor("#D32F2F"));
        logoutBtn.setTextColor(Color.WHITE); logoutBtn.setLayoutParams(fullWidthParams); mainLayout.addView(logoutBtn);

        db.collection("users").document(loggedInSchool).addSnapshotListener((doc, e) -> {
            if (e != null || doc == null || !doc.exists()) return;
            
            String perdayStr = doc.getString("perday_sms");
            isUnlimitedPlan = perdayStr != null && perdayStr.equalsIgnoreCase("unlimited");
            try { currentPerdayLimit = isUnlimitedPlan ? 999999 : Long.parseLong(perdayStr); } catch(Exception ex){ currentPerdayLimit = 0; }
            
            currentTotalLimit = doc.contains("total_sms") ? doc.getLong("total_sms") : 0;
            currentTotalUsed = doc.contains("used_sms") ? doc.getLong("used_sms") : 0;
            currentDailyUsed = doc.contains("daily_used") ? doc.getLong("daily_used") : 0;
            currentActiveDate = doc.contains("last_active_date") ? doc.getString("last_active_date") : "";
            
            String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date());
            if (!todayDate.equals(currentActiveDate)) {
                Map<String, Object> resets = new HashMap<>();
                resets.put("daily_used", 0);
                resets.put("last_active_date", todayDate);
                db.collection("users").document(loggedInSchool).update(resets);
                currentDailyUsed = 0;
                currentActiveDate = todayDate;
                limitToastShown = false; 
            }

            remainingSmsTxt.setText("Bache Hue SMS\n\n" + (isUnlimitedPlan ? "Unlimited" : (currentTotalLimit - currentTotalUsed)));
        });

        db.collection("sms_logs").whereEqualTo("school", loggedInSchool).addSnapshotListener((snaps, e) -> {
            if (snaps == null) return;
            int sent = 0, pending = 0, failed = 0;
            for (QueryDocumentSnapshot d : snaps) {
                String stat = d.getString("status");
                if ("sent".equals(stat)) sent++;
                else if ("pending".equals(stat)) pending++;
                else if ("failed".equals(stat)) failed++;
            }
            sentSmsTxt.setText("Total Sent SMS\n\n" + sent); pendingSmsTxt.setText("Pending SMS\n\n" + pending); failedSmsTxt.setText("Failed SMS\n\n" + failed);
        });

        startAutoSmsSender();

        remainingSmsTxt.setOnClickListener(v -> showPlanDetails());
        sentSmsTxt.setOnClickListener(v -> fetchAndShowList("sent", "Sent SMS History"));
        pendingSmsTxt.setOnClickListener(v -> fetchAndShowList("pending", "Pending SMS"));
        failedSmsTxt.setOnClickListener(v -> fetchAndShowList("failed", "Failed SMS"));
        linkSchoolBox.setOnClickListener(v -> showLinkMultipleSchoolDialog());
        
        logoutBtn.setOnClickListener(v -> {
            db.collection("users").document(loggedInSchool).update("school_device_id", "");
            sharedPreferences.edit().putString("last_active_school", "").apply(); // CLEAR AUTO LOGIN
            loggedInSchool = ""; showLoginScreen();
        });
    }

    private boolean canSendMoreSms() {
        if (isAdminMode) return true;
        if (isUnlimitedPlan) return true;
        if (currentTotalUsed >= currentTotalLimit) return false;
        if (currentDailyUsed >= currentPerdayLimit) return false;
        return true;
    }

    private void startAutoSmsSender() {
        db.collection("sms_logs")
          .whereEqualTo("school", loggedInSchool)
          .whereEqualTo("status", "pending")
          .addSnapshotListener((snapshots, e) -> {
              if (e != null || snapshots == null) return;
              
              for (DocumentChange dc : snapshots.getDocumentChanges()) {
                  if (dc.getType() == DocumentChange.Type.ADDED) {
                      String phone = dc.getDocument().getString("phone");
                      String msg = dc.getDocument().getString("msg");
                      String docId = dc.getDocument().getId();
                      
                      if (phone != null && msg != null && !phone.isEmpty()) {
                          if (canSendMoreSms()) {
                              limitToastShown = false; 
                              sendSmsWithDualSim(phone, msg, docId);
                          } else {
                              if (!limitToastShown) {
                                  Toast.makeText(MainActivity.this, "Daily SMS Limit Reached! Baki SMS pending me rakhe gaye hain jo kal jayenge.", Toast.LENGTH_LONG).show();
                                  limitToastShown = true;
                              }
                          }
                      }
                  }
              }
          });
    }

    private void showLinkMultipleSchoolDialog() {
        ScrollView dialogScroll = new ScrollView(this); LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL); dialogLayout.setPadding(30, 30, 30, 30); dialogScroll.addView(dialogLayout);

        TextView tableTitle = new TextView(this); tableTitle.setText("Linked Accounts:"); tableTitle.setTextSize(16f);
        tableTitle.setPadding(0, 0, 0, 15); dialogLayout.addView(tableTitle);

        TableLayout table = new TableLayout(this); table.setStretchAllColumns(true);
        TableRow headerRow = new TableRow(this); TextView h1 = new TextView(this); h1.setText("Account ID"); h1.setTextColor(Color.BLACK);
        TextView h2 = new TextView(this); h2.setText("Action"); h2.setTextColor(Color.BLACK); headerRow.addView(h1); headerRow.addView(h2); table.addView(headerRow);

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Account Switcher").setView(dialogScroll).show();

        if (sharedPreferences.getBoolean("is_admin_device", false)) {
            TableRow adminRow = new TableRow(this); TextView adminNameTv = new TextView(this); adminNameTv.setText("⭐ Admin Panel"); adminNameTv.setTextColor(Color.parseColor("#1A237E"));
            Button switchAdminBtn = new Button(this);
            if (isAdminMode) { switchAdminBtn.setText("Active"); switchAdminBtn.setEnabled(false); } 
            else { switchAdminBtn.setText("Switch"); switchAdminBtn.setBackgroundColor(Color.parseColor("#1A237E")); switchAdminBtn.setTextColor(Color.WHITE);
                   switchAdminBtn.setOnClickListener(v -> { 
                       dialog.dismiss(); isAdminMode = true; loggedInSchool = ""; 
                       sharedPreferences.edit().putBoolean("is_admin_active_session", true).apply();
                       showAdminDashboard(); 
                   }); }
            adminRow.addView(adminNameTv); adminRow.addView(switchAdminBtn); table.addView(adminRow);
        }

        String linkedListStr = sharedPreferences.getString("linked_list", "");
        if (!linkedListStr.isEmpty()) {
            String[] schools = linkedListStr.split(",");
            for (String schoolUser : schools) {
                if(schoolUser.isEmpty()) continue;
                TableRow row = new TableRow(this); TextView nameTv = new TextView(this); nameTv.setText(schoolUser);
                Button switchBtn = new Button(this);
                if (!isAdminMode && schoolUser.equals(loggedInSchool)) { switchBtn.setText("Active"); switchBtn.setEnabled(false); } 
                else { switchBtn.setText("Switch"); switchBtn.setBackgroundColor(Color.parseColor("#1E88E5")); switchBtn.setTextColor(Color.WHITE);
                       switchBtn.setOnClickListener(v -> { 
                           String savedPass = sharedPreferences.getString("pass_" + schoolUser, ""); 
                           dialog.dismiss(); 
                           handleSchoolLogin(schoolUser, savedPass, false, -1); // Auto switch keeps old SIM
                       }); }
                row.addView(nameTv); row.addView(switchBtn); table.addView(row);
            }
        }
        dialogLayout.addView(table);

        TextView addTitle = new TextView(this); addTitle.setText("\nLink New School Account:"); addTitle.setTextSize(16f); dialogLayout.addView(addTitle);
        EditText newUsername = new EditText(this); newUsername.setHint("School Username"); dialogLayout.addView(newUsername);
        EditText newPassword = new EditText(this); newPassword.setHint("School Password"); dialogLayout.addView(newPassword);
        
        // 🚨 [NEW] SIM SELECTION FOR LINKED ACCOUNTS 🚨
        Spinner simSpinner = new Spinner(this);
        List<Integer> subIds = new ArrayList<>();
        List<String> simNames = new ArrayList<>();
        simNames.add("Default SIM (Auto)");
        subIds.add(-1);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            SubscriptionManager subManager = SubscriptionManager.from(this);
            List<SubscriptionInfo> simInfoList = subManager.getActiveSubscriptionInfoList();
            if (simInfoList != null) {
                for (int i = 0; i < simInfoList.size(); i++) {
                    SubscriptionInfo info = simInfoList.get(i);
                    simNames.add("SIM " + (i + 1) + " (" + info.getCarrierName() + ")");
                    subIds.add(info.getSubscriptionId());
                }
            }
        }
        ArrayAdapter<String> simAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, simNames);
        simSpinner.setAdapter(simAdapter);
        dialogLayout.addView(simSpinner);

        Button linkBtn = new Button(this); linkBtn.setText("LINK ACCOUNT"); linkBtn.setBackgroundColor(Color.parseColor("#E65100")); linkBtn.setTextColor(Color.WHITE); dialogLayout.addView(linkBtn);

        linkBtn.setOnClickListener(v -> {
            String u = newUsername.getText().toString().trim(); String p = newPassword.getText().toString().trim();
            int selectedSim = subIds.get(simSpinner.getSelectedItemPosition());
            
            if(u.isEmpty() || p.isEmpty()) return;
            db.collection("users").document(u).get().addOnSuccessListener(doc -> {
                if (doc.exists() && p.equals(doc.getString("password"))) {
                    sharedPreferences.edit().putString("pass_" + u, p).apply();
                    if(selectedSim != -1) sharedPreferences.edit().putInt("sim_" + u, selectedSim).apply(); // Save SIM for new linked school
                    
                    String currentList = sharedPreferences.getString("linked_list", "");
                    if (!currentList.contains(u)) { currentList = currentList.isEmpty() ? u : currentList + "," + u; sharedPreferences.edit().putString("linked_list", currentList).apply(); }
                    Toast.makeText(this, "Linked successfully!", Toast.LENGTH_SHORT).show(); dialog.dismiss(); showLinkMultipleSchoolDialog();
                } else { Toast.makeText(this, "Galat Password!", Toast.LENGTH_SHORT).show(); }
            });
        });
    }

    private TextView createBox(String title, String colorHex) {
        TextView box = new TextView(this); box.setText(title + "\n\n..."); box.setTextSize(15f); box.setTextColor(Color.WHITE);
        box.setGravity(Gravity.CENTER); box.setPadding(20, 45, 20, 45);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); params.setMargins(15, 15, 15, 15);
        box.setLayoutParams(params);
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(15); shape.setColor(Color.parseColor(colorHex)); box.setBackground(shape);
        return box;
    }

    private void showPlanDetails() {
        String info = "Total SMS: " + currentTotalLimit +
                      "\nPerday Limit: " + (isUnlimitedPlan ? "Unlimited" : currentPerdayLimit) +
                      "\nDaily Used Today: " + currentDailyUsed +
                      "\nTotal Used: " + currentTotalUsed;
        showAlert("SMS Plan Details", info);
    }

    private void fetchAndShowList(String status, String title) {
        // [Existing List Dialog Code Unchanged]
        db.collection("sms_logs").whereEqualTo("school", loggedInSchool).whereEqualTo("status", status)
          .get().addOnSuccessListener(docs -> {
              LinearLayout listLayout = new LinearLayout(this); listLayout.setOrientation(LinearLayout.VERTICAL); listLayout.setPadding(20, 20, 20, 20);
              if (docs.isEmpty()) { TextView empty = new TextView(this); empty.setText("Koi data nahi mila."); listLayout.addView(empty); }

              for (QueryDocumentSnapshot d : docs) {
                  TextView tv = new TextView(this); tv.setText("📱 " + d.getString("phone") + "\n📅 " + d.getString("date") + "\n💬 " + d.getString("msg"));
                  tv.setPadding(10, 20, 10, 10); tv.setBackgroundColor(Color.parseColor("#EEEEEE"));
                  LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); p.setMargins(0, 10, 0, 10);
                  tv.setLayoutParams(p); listLayout.addView(tv);

                  if (status.equals("pending") || status.equals("failed")) {
                      Button sendBtn = new Button(this); sendBtn.setText("SEND NOW"); sendBtn.setBackgroundColor(Color.parseColor("#00897B")); sendBtn.setTextColor(Color.WHITE);
                      sendBtn.setOnClickListener(v -> {
                          if (canSendMoreSms()) {
                              sendSmsWithDualSim(d.getString("phone"), d.getString("msg"), d.getId());
                          } else {
                              Toast.makeText(MainActivity.this, "Daily SMS Limit Reached! Kal try karein.", Toast.LENGTH_LONG).show();
                          }
                      });
                      listLayout.addView(sendBtn);
                  }
              }
              ScrollView scroll = new ScrollView(this); scroll.addView(listLayout);
              new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
          });
    }

   // ==================== 🚨 [UPDATE] SMS DELIVERY ENGINE 🚨 ====================
    private void sendSmsWithDualSim(String phone, String msg, String docId) {
        Intent intent = new Intent(this, SmsResultReceiver.class);
        intent.setAction("SMS_SENT_ACTION");
        intent.putExtra("docId", docId);
        intent.putExtra("schoolId", loggedInSchool);
        intent.putExtra("isAdmin", isAdminMode);
        
        PendingIntent sentPI = PendingIntent.getBroadcast(this, docId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            SmsManager smsManager;
            
            // 🚨 READ SAVED SIM PREFERENCE FOR THIS SCHOOL 🚨
            int savedSimId = sharedPreferences.getInt("sim_" + loggedInSchool, -1);

            if (savedSimId != -1 && ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                // User ne specially koi SIM chuna tha login ke waqt
                smsManager = SmsManager.getSmsManagerForSubscriptionId(savedSimId);
            } else {
                // Default System Logic (Agar SIM na chuna ho)
                SubscriptionManager subManager = SubscriptionManager.from(this);
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    List<SubscriptionInfo> simInfoList = subManager.getActiveSubscriptionInfoList();
                    if (simInfoList != null && simInfoList.size() > 0) {
                        smsManager = SmsManager.getSmsManagerForSubscriptionId(simInfoList.get(0).getSubscriptionId());
                    } else {
                        smsManager = SmsManager.getDefault();
                    }
                } else {
                    smsManager = SmsManager.getDefault();
                }
            }
            
            ArrayList<String> parts = smsManager.divideMessage(msg);
            ArrayList<PendingIntent> sentIntents = new ArrayList<>();
            
            for (int i = 0; i < parts.size(); i++) {
                if (i == parts.size() - 1) {
                    sentIntents.add(sentPI); 
                } else {
                    sentIntents.add(null);
                }
            }

            smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null);
            
        } catch (Exception ex) {
            Toast.makeText(this, "Send Error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            db.collection("sms_logs").document(docId).update("status", "failed");
        }
    }

    private void showAlert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }
}
