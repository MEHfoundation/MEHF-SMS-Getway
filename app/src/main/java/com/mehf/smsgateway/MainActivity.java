package com.mehf.smsgateway;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.firebase.firestore.DocumentSnapshot;
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
            showLoginScreen();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        showLoginScreen(); 
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
        RadioButton rdoSchool = new RadioButton(this); rdoSchool.setText("School"); rdoSchool.setChecked(true);
        RadioButton rdoAdmin = new RadioButton(this); rdoAdmin.setText("Admin");
        roleGroup.addView(rdoSchool); roleGroup.addView(rdoAdmin);
        mainLayout.addView(roleGroup);

        EditText usernameInput = new EditText(this);
        usernameInput.setHint("Username (School ID)");
        mainLayout.addView(usernameInput);

        EditText passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        mainLayout.addView(passwordInput);

        Button loginBtn = new Button(this);
        loginBtn.setText("LOGIN");
        loginBtn.setBackgroundColor(Color.parseColor("#2E7D32"));
        loginBtn.setTextColor(Color.WHITE);
        
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, 40, 0, 0);
        loginBtn.setLayoutParams(btnParams);
        mainLayout.addView(loginBtn);

        loginBtn.setOnClickListener(v -> {
            String user = usernameInput.getText().toString().trim();
            String pass = passwordInput.getText().toString().trim();

            if (rdoAdmin.isChecked()) {
                handleAdminLogin();
            } else {
                handleSchoolLogin(user, pass, true);
            }
        });
    }

    private void handleAdminLogin() {
        Toast.makeText(this, "Verifying Admin...", Toast.LENGTH_SHORT).show();
        db.collection("system_settings").document("admin_data").get().addOnSuccessListener(doc -> {
            if (doc.exists() && doc.contains("admin_device_id")) {
                String savedId = doc.getString("admin_device_id");
                if (deviceId.equals(savedId)) {
                    // Local memory me save karna ki ye admin device hai
                    sharedPreferences.edit().putBoolean("is_admin_device", true).apply();
                    isAdminMode = true;
                    showAdminDashboard();
                } else {
                    showAlert("Notice", "Admin is already logged in on another device. Please select School or contact administrator.");
                }
            } else {
                db.collection("system_settings").document("admin_data")
                  .set(new HashMap<String, Object>() {{ put("admin_device_id", deviceId); }});
                sharedPreferences.edit().putBoolean("is_admin_device", true).apply();
                isAdminMode = true;
                showAdminDashboard();
            }
        });
    }

    private void handleSchoolLogin(String username, String password, boolean isFirstLogin) {
        if(username.isEmpty() || password.isEmpty()){
             Toast.makeText(this, "Username aur Password bharein!", Toast.LENGTH_SHORT).show();
             return;
        }
        db.collection("schools").document(username).get().addOnSuccessListener(doc -> {
            if (doc.exists()) {
                String savedPass = doc.getString("password");
                if (password.equals(savedPass)) {
                    loggedInSchool = username;
                    isAdminMode = false;
                    
                    // Account linking memory settings
                    sharedPreferences.edit().putString("pass_" + username, password).apply();
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
                Toast.makeText(this, "School ID nahi mila!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== 2. ADMIN DASHBOARD (6 BOXES NOW) ====================
    private void showAdminDashboard() {
        mainLayout.removeAllViews();
        mainLayout.setPadding(30, 30, 30, 30);

        TextView header = new TextView(this);
        header.setText("MEHF ADMIN PANEL\n(SMS Status: Unlimited)");
        header.setTextSize(20f);
        header.setTextColor(Color.WHITE);
        header.setBackgroundColor(Color.parseColor("#1A237E"));
        header.setPadding(30, 40, 30, 40);
        header.setGravity(Gravity.CENTER);
        mainLayout.addView(header);

        LinearLayout row1 = new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        
        remainingSmsTxt = createBox("Bache Hue SMS", "#43A047");
        sentSmsTxt = createBox("Total Sent SMS", "#1E88E5");
        pendingSmsTxt = createBox("Pending SMS", "#FDD835");
        failedSmsTxt = createBox("Failed SMS", "#E53935");

        remainingSmsTxt.setText("Bache Hue SMS\n\nUnlimited");

        row1.addView(remainingSmsTxt); row1.addView(sentSmsTxt);
        row2.addView(pendingSmsTxt); row2.addView(failedSmsTxt);
        mainLayout.addView(row1); mainLayout.addView(row2);

        LinearLayout.LayoutParams fullWidthParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fullWidthParams.setMargins(15, 15, 15, 15);

        // Box 5: Manage School Plan
        TextView manageSchoolBox = createBox("⚙️ Manage School Plan", "#6A1B9A");
        manageSchoolBox.setLayoutParams(fullWidthParams);
        mainLayout.addView(manageSchoolBox);

        // Box 6: Admin Multiple School Link/Switch Option (NEW)
        TextView linkSchoolBox = createBox("🔗 Link Multiple School / Switch Account", "#E65100");
        linkSchoolBox.setLayoutParams(fullWidthParams);
        mainLayout.addView(linkSchoolBox);

        loadAdminSystemLogs();

        manageSchoolBox.setOnClickListener(v -> showManageSchoolDialog());
        linkSchoolBox.setOnClickListener(v -> showLinkMultipleSchoolDialog());
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
            sentSmsTxt.setText("Total Sent SMS\n\n" + sent);
            pendingSmsTxt.setText("Pending SMS\n\n" + pending);
            failedSmsTxt.setText("Failed SMS\n\n" + failed);
        });
    }

    // Admin Action: Plan Creator Form
    private void showManageSchoolDialog() {
        Toast.makeText(this, "Loading Schools...", Toast.LENGTH_SHORT).show();
        db.collection("schools").get().addOnSuccessListener(docs -> {
            ArrayList<String> schoolList = new ArrayList<>();
            for (DocumentSnapshot d : docs) {
                schoolList.add(d.getId());
            }

            LinearLayout dialogLayout = new LinearLayout(this);
            dialogLayout.setOrientation(LinearLayout.VERTICAL);
            dialogLayout.setPadding(40, 40, 40, 40);

            TextView lbl = new TextView(this); lbl.setText("School Chunein:");
            dialogLayout.addView(lbl);

            Spinner spinner = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, schoolList);
            spinner.setAdapter(adapter);
            dialogLayout.addView(spinner);

            EditText limitInput = new EditText(this); limitInput.setHint("Perday SMS Limit (Type 'unlimited' or Number)");
            dialogLayout.addView(limitInput);

            EditText startInput = new EditText(this); startInput.setHint("Start Date (YYYY-MM-DD)");
            dialogLayout.addView(startInput);

            EditText expiryInput = new EditText(this); expiryInput.setHint("Expiry Date (YYYY-MM-DD)");
            dialogLayout.addView(expiryInput);

            Button submitBtn = new Button(this);
            submitBtn.setText("FINISH & SUBMIT PLAN");
            submitBtn.setBackgroundColor(Color.parseColor("#6A1B9A"));
            submitBtn.setTextColor(Color.WHITE);
            dialogLayout.addView(submitBtn);

            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Manage School Plan").setView(dialogLayout).show();

            submitBtn.setOnClickListener(v -> {
                String selectedSchool = spinner.getSelectedItem().toString();
                String limitVal = limitInput.getText().toString().trim();
                String startD = startInput.getText().toString().trim();
                String expD = expiryInput.getText().toString().trim();

                if(limitVal.isEmpty() || startD.isEmpty() || expD.isEmpty()) {
                    Toast.makeText(this, "Saari details bharein!", Toast.LENGTH_SHORT).show();
                    return;
                }

                Map<String, Object> updateData = new HashMap<>();
                updateData.put("perday_sms", limitVal);
                updateData.put("start_date", startD);
                updateData.put("expiry_date", expD);
                updateData.put("activation_date", startD);
                updateData.put("renew_time", "12:00 AM");
                if(!limitVal.equalsIgnoreCase("unlimited")) {
                    updateData.put("total_sms", Long.parseLong(limitVal) * 30); 
                } else {
                    updateData.put("total_sms", 999999);
                }
                updateData.put("used_sms", 0);

                db.collection("schools").document(selectedSchool).update(updateData).addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Plan successfully update ho gaya!", Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                });
            });
        });
    }

    // ==================== 3. SCHOOL DASHBOARD ====================
    private void showSchoolDashboard(DocumentSnapshot schoolData) {
        mainLayout.removeAllViews();
        mainLayout.setPadding(30, 30, 30, 30);

        TextView header = new TextView(this);
        header.setText(schoolData.getString("school_name") + "\nSMS Dashboard");
        header.setTextSize(20f);
        header.setTextColor(Color.WHITE);
        header.setBackgroundColor(Color.parseColor("#3949AB"));
        header.setPadding(30, 40, 30, 40);
        header.setGravity(Gravity.CENTER);
        mainLayout.addView(header);

        LinearLayout row1 = new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        
        remainingSmsTxt = createBox("Bache Hue SMS", "#43A047");
        sentSmsTxt = createBox("Total Sent SMS", "#1E88E5");
        pendingSmsTxt = createBox("Pending SMS", "#FDD835");
        failedSmsTxt = createBox("Failed SMS", "#E53935");

        row1.addView(remainingSmsTxt); row1.addView(sentSmsTxt);
        row2.addView(pendingSmsTxt); row2.addView(failedSmsTxt);
        mainLayout.addView(row1); mainLayout.addView(row2);

        // Switcher box for school dashboard
        TextView linkSchoolBox = createBox("🔗 Link Multiple School / Switch Account", "#E65100");
        LinearLayout.LayoutParams fullWidthParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fullWidthParams.setMargins(15, 20, 15, 20);
        linkSchoolBox.setLayoutParams(fullWidthParams);
        mainLayout.addView(linkSchoolBox);

        loadSchoolDashboardData(schoolData);

        remainingSmsTxt.setOnClickListener(v -> showPlanDetails(schoolData));
        sentSmsTxt.setOnClickListener(v -> fetchAndShowList("sent", "Sent SMS History"));
        pendingSmsTxt.setOnClickListener(v -> fetchAndShowList("pending", "Pending SMS"));
        failedSmsTxt.setOnClickListener(v -> fetchAndShowList("failed", "Failed SMS"));
        
        linkSchoolBox.setOnClickListener(v -> showLinkMultipleSchoolDialog());
    }

    private void loadSchoolDashboardData(DocumentSnapshot doc) {
        long total = doc.getLong("total_sms") != null ? doc.getLong("total_sms") : 0;
        long used = doc.getLong("used_sms") != null ? doc.getLong("used_sms") : 0;
        remainingSmsTxt.setText("Bache Hue SMS\n\n" + (total - used));

        db.collection("sms_logs").whereEqualTo("school", loggedInSchool).addSnapshotListener((snaps, e) -> {
            if (snaps == null) return;
            int sent = 0, pending = 0, failed = 0;
            for (QueryDocumentSnapshot d : snaps) {
                String stat = d.getString("status");
                if ("sent".equals(stat)) sent++;
                else if ("pending".equals(stat)) pending++;
                else if ("failed".equals(stat)) failed++;
            }
            sentSmsTxt.setText("Total Sent SMS\n\n" + sent);
            pendingSmsTxt.setText("Pending SMS\n\n" + pending);
            failedSmsTxt.setText("Failed SMS\n\n" + failed);
        });
    }

    // ==================== 4. MASTER ACCOUNT LINK / SWITCH DIALOG ====================
    private void showLinkMultipleSchoolDialog() {
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(30, 30, 30, 30);

        TextView tableTitle = new TextView(this);
        tableTitle.setText("Linked Accounts Table:");
        tableTitle.setTextSize(16f);
        tableTitle.setPadding(0, 0, 0, 15);
        dialogLayout.addView(tableTitle);

        TableLayout table = new TableLayout(this);
        table.setStretchAllColumns(true);
        
        TableRow headerRow = new TableRow(this);
        TextView h1 = new TextView(this); h1.setText("Account / School ID"); h1.setTextSize(14f); h1.setTextColor(Color.BLACK);
        TextView h2 = new TextView(this); h2.setText("Action"); h2.setTextSize(14f); h2.setTextColor(Color.BLACK);
        headerRow.addView(h1); headerRow.addView(h2);
        table.addView(headerRow);

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Account Switcher Control").setView(dialogLayout).show();

        // [SMART FEATURE]: Agar ye device admin ka hai, to table me Admin mode par wapas jaane ka router jodna
        if (sharedPreferences.getBoolean("is_admin_device", false)) {
            TableRow adminRow = new TableRow(this);
            TextView adminNameTv = new TextView(this); adminNameTv.setText("⭐ MEHF Admin Panel");
            adminNameTv.setTextColor(Color.parseColor("#1A237E"));
            
            Button switchAdminBtn = new Button(this);
            if (isAdminMode) {
                switchAdminBtn.setText("Active");
                switchAdminBtn.setEnabled(false);
            } else {
                switchAdminBtn.setText("Switch");
                switchAdminBtn.setBackgroundColor(Color.parseColor("#1A237E"));
                switchAdminBtn.setTextColor(Color.WHITE);
                switchAdminBtn.setOnClickListener(v -> {
                    dialog.dismiss();
                    isAdminMode = true;
                    loggedInSchool = "";
                    showAdminDashboard();
                });
            }
            adminRow.addView(adminNameTv);
            adminRow.addView(switchAdminBtn);
            table.addView(adminRow);
        }

        // Baki bache schools ki list table me populate karna
        String linkedListStr = sharedPreferences.getString("linked_list", "");
        if (!linkedListStr.isEmpty()) {
            String[] schools = linkedListStr.split(",");
            for (String schoolUser : schools) {
                if(schoolUser.isEmpty()) continue;
                TableRow row = new TableRow(this);
                TextView nameTv = new TextView(this); nameTv.setText(schoolUser);
                
                Button switchBtn = new Button(this);
                if (!isAdminMode && schoolUser.equals(loggedInSchool)) {
                    switchBtn.setText("Active");
                    switchBtn.setEnabled(false);
                } else {
                    switchBtn.setText("Switch");
                    switchBtn.setBackgroundColor(Color.parseColor("#1E88E5"));
                    switchBtn.setTextColor(Color.WHITE);
                    switchBtn.setOnClickListener(v -> {
                        String savedPass = sharedPreferences.getString("pass_" + schoolUser, "");
                        dialog.dismiss();
                        handleSchoolLogin(schoolUser, savedPass, false);
                    });
                }
                row.addView(nameTv);
                row.addView(switchBtn);
                table.addView(row);
            }
        }
        dialogLayout.addView(table);

        // Naya School Link karne ka form section नीचे
        TextView addTitle = new TextView(this);
        addTitle.setText("\nLink New School Account:");
        addTitle.setTextSize(16f);
        dialogLayout.addView(addTitle);

        EditText newUsername = new EditText(this); newUsername.setHint("School Username");
        dialogLayout.addView(newUsername);

        EditText newPassword = new EditText(this); newPassword.setHint("School Password");
        dialogLayout.addView(newPassword);

        Button linkBtn = new Button(this);
        linkBtn.setText("LINK & AUTHORIZE ACCOUNT");
        linkBtn.setBackgroundColor(Color.parseColor("#E65100"));
        linkBtn.setTextColor(Color.WHITE);
        dialogLayout.addView(linkBtn);

        linkBtn.setOnClickListener(v -> {
            String u = newUsername.getText().toString().trim();
            String p = newPassword.getText().toString().trim();
            if(u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "Details bharein!", Toast.LENGTH_SHORT).show();
                return;
            }
            db.collection("schools").document(u).get().addOnSuccessListener(doc -> {
                if (doc.exists() && p.equals(doc.getString("password"))) {
                    sharedPreferences.edit().putString("pass_" + u, p).apply();
                    String currentList = sharedPreferences.getString("linked_list", "");
                    if (!currentList.contains(u)) {
                        currentList = currentList.isEmpty() ? u : currentList + "," + u;
                        sharedPreferences.edit().putString("linked_list", currentList).apply();
                    }
                    Toast.makeText(this, "Account link ho gaya!", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    showLinkMultipleSchoolDialog(); // Refresh table view
                } else {
                    Toast.makeText(this, "Galat Password ya Username!", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private TextView createBox(String title, String colorHex) {
        TextView box = new TextView(this);
        box.setText(title + "\n\n...");
        box.setTextSize(15f);
        box.setTextColor(Color.WHITE);
        box.setGravity(Gravity.CENTER);
        box.setPadding(20, 45, 20, 45);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(15, 15, 15, 15);
        box.setLayoutParams(params);

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(15);
        shape.setColor(Color.parseColor(colorHex));
        box.setBackground(shape);

        return box;
    }

    // ==================== 5. DUAL SIM ENGINE & LOGGER ====================
    private void showPlanDetails(DocumentSnapshot doc) {
        String info = "Total SMS: " + doc.get("total_sms") +
                      "\nPerday Limit: " + doc.get("perday_sms") +
                      "\nActivation Date: " + doc.getString("activation_date") +
                      "\nExpiry Date: " + doc.getString("expiry_date") +
                      "\nRenew Time: " + doc.getString("renew_time");
        showAlert("SMS Plan Details", info);
    }

    private void fetchAndShowList(String status, String title) {
        db.collection("sms_logs").whereEqualTo("school", loggedInSchool).whereEqualTo("status", status)
          .get().addOnSuccessListener(docs -> {
              LinearLayout listLayout = new LinearLayout(this);
              listLayout.setOrientation(LinearLayout.VERTICAL);
              listLayout.setPadding(20, 20, 20, 20);

              if (docs.isEmpty()) {
                  TextView empty = new TextView(this);
                  empty.setText("Koi data nahi mila.");
                  listLayout.addView(empty);
              }

              for (QueryDocumentSnapshot d : docs) {
                  TextView tv = new TextView(this);
                  tv.setText("📱 " + d.getString("phone") + "\n📅 " + d.getString("date") + "\n💬 " + d.getString("msg"));
                  tv.setPadding(10, 20, 10, 10);
                  tv.setBackgroundColor(Color.parseColor("#EEEEEE"));
                  
                  LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                          LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                  p.setMargins(0, 10, 0, 10);
                  tv.setLayoutParams(p);
                  listLayout.addView(tv);

                  if (status.equals("pending") || status.equals("failed")) {
                      Button sendBtn = new Button(this);
                      sendBtn.setText("SEND NOW");
                      sendBtn.setBackgroundColor(Color.parseColor("#00897B"));
                      sendBtn.setTextColor(Color.WHITE);
                      sendBtn.setOnClickListener(v -> sendSmsWithDualSim(d.getString("phone"), d.getString("msg"), d.getId()));
                      listLayout.addView(sendBtn);
                  }
              }
              
              ScrollView scroll = new ScrollView(this);
              scroll.addView(listLayout);
              new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
          });
    }

    private void sendSmsWithDualSim(String phone, String msg, String docId) {
        try {
            SubscriptionManager subManager = SubscriptionManager.from(this);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                List<SubscriptionInfo> simInfoList = subManager.getActiveSubscriptionInfoList();
                
                if (simInfoList != null && simInfoList.size() > 0) {
                    try {
                        SmsManager sms1 = SmsManager.getSmsManagerForSubscriptionId(simInfoList.get(0).getSubscriptionId());
                        sms1.sendTextMessage(phone, null, msg, null, null);
                        updateStatus(docId, "sent");
                        Toast.makeText(this, "Sent via SIM 1", Toast.LENGTH_SHORT).show();
                    } catch (Exception e1) {
                        if (simInfoList.size() > 1) {
                            SmsManager sms2 = SmsManager.getSmsManagerForSubscriptionId(simInfoList.get(1).getSubscriptionId());
                            sms2.sendTextMessage(phone, null, msg, null, null);
                            updateStatus(docId, "sent");
                            Toast.makeText(this, "Sent via SIM 2", Toast.LENGTH_SHORT).show();
                        } else {
                            updateStatus(docId, "failed");
                        }
                    }
                } else {
                    SmsManager.getDefault().sendTextMessage(phone, null, msg, null, null);
                    updateStatus(docId, "sent");
                }
            }
        } catch (Exception ex) {
            updateStatus(docId, "failed");
        }
    }

    private void updateStatus(String docId, String status) {
        db.collection("sms_logs").document(docId).update("status", status);
    }

    private void showAlert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }
}
