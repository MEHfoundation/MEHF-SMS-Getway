package com.mehf.smsgateway;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private List<ChatMessage> chatList;
    private String currentUserId;

    public ChatAdapter(List<ChatMessage> chatList, String currentUserId) {
        this.chatList = chatList;
        this.currentUserId = currentUserId;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout layout = new LinearLayout(parent.getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        layout.setPadding(20, 10, 20, 10);
        
        TextView textView = new TextView(parent.getContext());
        textView.setTextSize(16f);
        textView.setPadding(30, 20, 30, 20);
        layout.addView(textView);
        
        return new ChatViewHolder(layout, textView);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        ChatMessage message = chatList.get(position);
        holder.textView.setText(message.getMessage());

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) holder.textView.getLayoutParams();
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(20);

        if (message.getSenderId() != null && message.getSenderId().equals(currentUserId)) {
            // Apna message (Right side - Green)
            params.gravity = Gravity.END;
            shape.setColor(Color.parseColor("#DCF8C6"));
            holder.textView.setTextColor(Color.BLACK);
        } else {
            // Samne wale ka message (Left side - White)
            params.gravity = Gravity.START;
            shape.setColor(Color.WHITE);
            holder.textView.setTextColor(Color.BLACK);
        }
        
        holder.textView.setLayoutParams(params);
        holder.textView.setBackground(shape);
    }

    @Override
    public int getItemCount() {
        return chatList.size();
    }

    public static class ChatViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        public ChatViewHolder(@NonNull View itemView, TextView textView) {
            super(itemView);
            this.textView = textView;
        }
    }
}
