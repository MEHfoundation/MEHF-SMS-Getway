package com.mehf.smsgateway;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private String deviceId;
    private LinearLayout mainLayout;
    private String loggedInSchool = "";
    
    // UI Elements
    private TextView remainingSmsTxt, sentSmsTxt, pendingSmsTxt, failedSmsTxt;

    @SuppressLint("HardwareIds")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = FirebaseFirestore.getInstance();
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.parseColor("#F4F6F9"));
        setContentView(mainLayout);

        checkPermissionsAndStart();
    }

    private void checkPermissionsAndStart() {
        String[] perms = {Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE};
        if (ContextCompat.checkSelfPermission(this, perms[0]) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, perms[1]) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, perms, 101);
        } else {
            showLoginScreen();
        }
    }

    // ==================== 1. LOGIN SCREEN ====================
    private void showLoginScreen() {
        mainLayout.removeAllViews();
        mainLayout.setPadding(60, 100, 60, 60);

        TextView title = new TextView(this);
        title.setText("MEHF School Login");
        title.setTextSize(28f);
        title.setTextColor(Color.parseColor("#1A237E"));
        title.setGravity(Gravity.CENTER);
        mainLayout.addView(title);

        RadioGroup roleGroup = new RadioGroup(this);
        roleGroup.setOrientation(LinearLayout.HORIZONTAL);
        roleGroup.setGravity(Gravity.CENTER);
        RadioButton rdoSchool = new RadioButton(this); rdoSchool.setText("School"); rdoSchool.setChecked(true);
        RadioButton rdoAdmin = new RadioButton(this); rdoAdmin.setText("Admin");
        roleGroup.addView(rdoSchool); roleGroup.addView(rdoAdmin);
        mainLayout.addView(roleGroup);

        EditText usernameInput = new EditText(this);
        usernameInput.setHint("Username");
        mainLayout.addView(usernameInput);

        EditText passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        mainLayout.addView(passwordInput);

        Button loginBtn = new Button(this);
        loginBtn.setText("LOGIN");
        loginBtn.setBackgroundColor(Color.parseColor("#2E7D32"));
        loginBtn.setTextColor(Color.WHITE);
        mainLayout.addView(loginBtn);

        loginBtn.setOnClickListener(v -> {
            String user = usernameInput.getText().toString().trim();
            String pass = passwordInput.getText().toString().trim();

            if (rdoAdmin.isChecked()) {
                handleAdminLogin();
            } else {
                handleSchoolLogin(user, pass);
            }
        });
    }

    private void handleAdminLogin() {
        db.collection("system_settings").document("admin_data").get().addOnSuccessListener(doc -> {
            if (doc.exists() && doc.contains("admin_device_id")) {
                String savedId = doc.getString("admin_device_id");
                if (savedId.equals(deviceId)) {
                    Toast.makeText(this, "Admin Login Success", Toast.LENGTH_SHORT).show();
                    // यहाँ एडमिन पैनल खुलेगा
                } else {
                    showAlert("Notice", "Admin is already logged in on another device. Please select School or contact administrator.");
                }
            } else {
                db.collection("system_settings").document("admin_data")
                  .update("admin_device_id", deviceId);
                Toast.makeText(this, "First Install: You are now Admin!", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handleSchoolLogin(String username, String password) {
        db.collection("schools").document(username).get().addOnSuccessListener(doc -> {
            if (doc.exists()) {
                String savedPass = doc.getString("password");
                if (password.equals(savedPass)) {
                    loggedInSchool = username;
                    showDashboard(doc);
                } else {
                    Toast.makeText(this, "गलत पासवर्ड!", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "School Username नहीं मिला!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== 2. DASHBOARD INTERFACE ====================
    private void showDashboard(DocumentSnapshot schoolData) {
        mainLayout.removeAllViews();
        mainLayout.setPadding(40, 40, 40, 40);

        // Header
        TextView header = new TextView(this);
        header.setText(schoolData.getString("school_name") + "\nSMS Dashboard");
        header.setTextSize(22f);
        header.setTextColor(Color.WHITE);
        header.setBackgroundColor(Color.parseColor("#3949AB"));
        header.setPadding(30, 40, 30, 40);
        header.setGravity(Gravity.CENTER);
        mainLayout.addView(header);

        // Grid Layout for Boxes
        LinearLayout row1 = new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        
        // Boxes
        remainingSmsTxt = createBox("Bache Hue SMS", "#43A047");
        sentSmsTxt = createBox("Total Sent SMS", "#1E88E5");
        pendingSmsTxt = createBox("Pending SMS", "#FDD835");
        failedSmsTxt = createBox("Failed SMS", "#E53935");

        row1.addView(remainingSmsTxt); row1.addView(sentSmsTxt);
        row2.addView(pendingSmsTxt); row2.addView(failedSmsTxt);
        mainLayout.addView(row1); mainLayout.addView(row2);

        loadDashboardData(schoolData);

        // Clicks
        remainingSmsTxt.setOnClickListener(v -> showPlanDetails(schoolData));
        sentSmsTxt.setOnClickListener(v -> fetchAndShowList("sent", "Sent SMS History"));
        pendingSmsTxt.setOnClickListener(v -> fetchAndShowList("pending", "Pending SMS (Click to Send)"));
        failedSmsTxt.setOnClickListener(v -> fetchAndShowList("failed", "Failed SMS (Click to Retry)"));
    }

    private TextView createBox(String title, String colorHex) {
        TextView box = new TextView(this);
        box.setText(title + "\n\n...");
        box.setTextSize(16f);
        box.setTextColor(Color.WHITE);
        box.setGravity(Gravity.CENTER);
        box.setPadding(20, 50, 20, 50);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(15, 20, 15, 20);
        box.setLayoutParams(params);

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(20);
        shape.setColor(Color.parseColor(colorHex));
        box.setBackground(shape);

        return box;
    }

    private void loadDashboardData(DocumentSnapshot doc) {
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

    // ==================== 3. DIALOGS & DUAL SIM LOGIC ====================
    private void showPlanDetails(DocumentSnapshot doc) {
        String info = "Total SMS: " + doc.getLong("total_sms") +
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

              for (QueryDocumentSnapshot d : docs) {
                  TextView tv = new TextView(this);
                  tv.setText("📱 " + d.getString("phone") + "\n📅 " + d.getString("date") + "\n💬 " + d.getString("msg"));
                  tv.setPadding(20, 20, 20, 20);
                  listLayout.addView(tv);

                  if (status.equals("pending") || status.equals("failed")) {
                      Button sendBtn = new Button(this);
                      sendBtn.setText("SEND NOW");
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
                    // Try SIM 1
                    try {
                        SmsManager sms1 = SmsManager.getSmsManagerForSubscriptionId(simInfoList.get(0).getSubscriptionId());
                        sms1.sendTextMessage(phone, null, msg, null, null);
                        updateStatus(docId, "sent");
                        Toast.makeText(this, "Sent via SIM 1", Toast.LENGTH_SHORT).show();
                    } catch (Exception e1) {
                        // Try SIM 2 if SIM 1 fails
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
                    // Default SMS Manager if SIM list not accessible
                    SmsManager.getDefault().sendTextMessage(phone, null, msg, null, null);
                    updateStatus(docId, "sent");
                }
            }
        } catch (Exception ex) {
            updateStatus(docId, "failed");
            Toast.makeText(this, "SMS Failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateStatus(String docId, String status) {
        db.collection("sms_logs").document(docId).update("status", status);
    }

    private void showAlert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }
}
