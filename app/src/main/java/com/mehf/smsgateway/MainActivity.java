package com.mehf.smsgateway;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.SmsManager;
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
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private TextView statusLog;
    private EditText schoolIdInput, limitInput, startDateInput, endDateInput, adminPinInput;
    private LinearLayout adminLayout, schoolLayout;
    private String schoolId = "";
    private static final int SMS_PERMISSION_CODE = 101;
    private final String ADMIN_PIN = "1999"; 

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);
        scrollView.addView(layout);

        TextView title = new TextView(this);
        title.setText("MEHF SMS Gateway & Admin Panel");
        title.setTextSize(22f);
        layout.addView(title);

        schoolIdInput = new EditText(this);
        schoolIdInput.setHint("School ID दर्ज करें (जैसे: success_junior)");
        layout.addView(schoolIdInput);

        RadioGroup roleGroup = new RadioGroup(this);
        RadioButton rdoAdmin = new RadioButton(this);
        rdoAdmin.setText("MEHF Admin");
        RadioButton rdoSchool = new RadioButton(this);
        rdoSchool.setText("School Gateway");
        roleGroup.addView(rdoAdmin);
        roleGroup.addView(rdoSchool);
        layout.addView(roleGroup);

        adminLayout = new LinearLayout(this);
        adminLayout.setOrientation(LinearLayout.VERTICAL);
        adminLayout.setVisibility(View.GONE);

        adminPinInput = new EditText(this);
        adminPinInput.setHint("Admin PIN दर्ज करें");
        adminLayout.addView(adminPinInput);

        limitInput = new EditText(this);
        limitInput.setHint("डेली SMS लिमिट (जैसे: 500)");
        adminLayout.addView(limitInput);

        startDateInput = new EditText(this);
        startDateInput.setHint("शुरुआती तारीख (YYYY-MM-DD)");
        adminLayout.addView(startDateInput);

        endDateInput = new EditText(this);
        endDateInput.setHint("अंतिम तारीख (YYYY-MM-DD)");
        adminLayout.addView(endDateInput);

        Button savePlanBtn = new Button(this);
        savePlanBtn.setText("स्कूल का प्लान सेव करें");
        adminLayout.addView(savePlanBtn);
        layout.addView(adminLayout);

        schoolLayout = new LinearLayout(this);
        schoolLayout.setOrientation(LinearLayout.VERTICAL);
        schoolLayout.setVisibility(View.GONE);

        Button startGatewayBtn = new Button(this);
        startGatewayBtn.setText("गेटवे चालू करें (Start System)");
        schoolLayout.addView(startGatewayBtn);

        Button viewHistoryBtn = new Button(this);
        viewHistoryBtn.setText("SMS रिपोर्ट (हिस्ट्री) देखें");
        schoolLayout.addView(viewHistoryBtn);

        layout.addView(schoolLayout);

        statusLog = new TextView(this);
        statusLog.setText("\nस्टेटस: इंतज़ार कर रहा है...");
        layout.addView(statusLog);

        setContentView(scrollView);

        roleGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == rdoAdmin.getId()) {
                adminLayout.setVisibility(View.VISIBLE);
                schoolLayout.setVisibility(View.GONE);
            } else {
                adminLayout.setVisibility(View.GONE);
                schoolLayout.setVisibility(View.VISIBLE);
            }
        });

        savePlanBtn.setOnClickListener(v -> {
            if (!adminPinInput.getText().toString().equals(ADMIN_PIN)) {
                Toast.makeText(this, "गलत PIN!", Toast.LENGTH_SHORT).show();
                return;
            }
            saveSchoolPlan();
        });

        startGatewayBtn.setOnClickListener(v -> {
            schoolId = schoolIdInput.getText().toString().trim();
            if (schoolId.isEmpty()) {
                Toast.makeText(this, "School ID डालें!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
            } else {
                startListeningToFirebase();
            }
        });

        viewHistoryBtn.setOnClickListener(v -> {
            schoolId = schoolIdInput.getText().toString().trim();
            if (!schoolId.isEmpty()) fetchSmsHistory();
        });
    }

    private void saveSchoolPlan() {
        schoolId = schoolIdInput.getText().toString().trim();
        db = FirebaseFirestore.getInstance();
        Map<String, Object> plan = new HashMap<>();
        plan.put("daily_limit", Integer.parseInt(limitInput.getText().toString()));
        plan.put("start_date", startDateInput.getText().toString());
        plan.put("end_date", endDateInput.getText().toString());
        plan.put("sent_today", 0);
        plan.put("last_active_date", "");

        db.collection("school_limits").document(schoolId).set(plan)
            .addOnSuccessListener(aVoid -> Toast.makeText(this, "प्लान अपडेट हो गया!", Toast.LENGTH_LONG).show());
    }

    private void fetchSmsHistory() {
        db = FirebaseFirestore.getInstance();
        statusLog.setText("\nडेटाबेस चेक हो रहा है...");
        db.collection("pending_sms").whereEqualTo("school_id", schoolId).get()
            .addOnSuccessListener(docs -> {
                StringBuilder history = new StringBuilder("\n--- SMS रिपोर्ट ---\n");
                int total = 0, sent = 0, pending = 0;
                for (QueryDocumentSnapshot doc : docs) {
                    total++;
                    String status = doc.getString("status");
                    if ("sent".equals(status)) sent++; else if ("pending".equals(status)) pending++;
                    history.append("📱 ").append(doc.getString("phone")).append(" - ").append(status).append("\n");
                }
                history.insert(0, "\nकुल SMS: " + total + " | भेजे गए: " + sent + " | पेंडिंग: " + pending + "\n");
                statusLog.setText(history.toString());
            });
    }

    private void startListeningToFirebase() {
        db = FirebaseFirestore.getInstance();
        statusLog.setText("\n✅ गेटवे चालू है। नये SMS का इंतज़ार...");
        db.collection("pending_sms").whereEqualTo("status", "pending").whereEqualTo("school_id", schoolId)
            .addSnapshotListener((snaps, e) -> {
                if (e != null || snaps == null) return;
                for (DocumentChange dc : snaps.getDocumentChanges()) {
                    if (dc.getType() == DocumentChange.Type.ADDED) {
                        checkPlanAndLimit(dc.getDocument().getString("phone"), dc.getDocument().getString("message"), dc.getDocument().getId());
                    }
                }
            });
    }

    private void checkPlanAndLimit(String phoneNo, String msg, String docId) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        db.collection("school_limits").document(schoolId).get().addOnSuccessListener(doc -> {
            if (doc.exists()) {
                String startDate = doc.getString("start_date");
                String endDate = doc.getString("end_date");
                long limit = doc.getLong("daily_limit") != null ? doc.getLong("daily_limit") : 0;
                long sent = doc.getLong("sent_today") != null ? doc.getLong("sent_today") : 0;
                String lastDate = doc.getString("last_active_date");

                if (today.compareTo(startDate) < 0) { statusLog.append("\n❌ प्लान अभी शुरू नहीं हुआ है।"); return; }
                if (today.compareTo(endDate) > 0) { statusLog.append("\n❌ प्लान एक्सपायर हो चुका है!"); return; }
                if (lastDate == null || !today.equals(lastDate)) { sent = 0; db.collection("school_limits").document(schoolId).update("last_active_date", today, "sent_today", 0); }

                if (sent < limit) {
                    try {
                        SmsManager.getDefault().sendTextMessage(phoneNo, null, msg, null, null);
                        db.collection("pending_sms").document(docId).update("status", "sent");
                        db.collection("school_limits").document(schoolId).update("sent_today", sent + 1);
                        statusLog.append("\n➜ SMS भेजा गया: " + phoneNo);
                    } catch (Exception ex) { db.collection("pending_sms").document(docId).update("status", "failed"); }
                } else {
                    statusLog.append("\n⏳ आज की लिमिट खत्म। SMS Pending है।");
                }
            } else { statusLog.append("\n❌ एडमिन ने प्लान सेट नहीं किया है।"); }
        });
    }
}
