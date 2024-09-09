package com.google.ar.core.helpers;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.FirebaseApp;
import com.google.firebase.database.*;

public class FirebaseManager {

    public interface CloudAnchorIdListener {
        void onCloudAnchorIdAvailable(String cloudAnchorId);
    }

    public interface ShortCodeListener {
        void onShortCodeAvailable(Integer shortCode);
        void onError(String errorMessage);
    }

    private static final String TAG = FirebaseManager.class.getName();
    private static final String KEY_ROOT_DIR = "shared_anchor_codelab_root";
    private static final String KEY_NEXT_SHORT_CODE = "next_short_code";
    private static final String KEY_PREFIX = "anchor;";
    private static final int INITIAL_SHORT_CODE = 001;
    private final DatabaseReference rootRef;

    public FirebaseManager(Context context) {
        Log.d(TAG, "sbbbInitializing FirebaseManager");
        FirebaseApp firebaseApp = FirebaseApp.initializeApp(context);
        FirebaseDatabase database = FirebaseDatabase.getInstance("https://palmpet2-0-default-rtdb.europe-west1.firebasedatabase.app/");
        rootRef = database.getReference().child(KEY_ROOT_DIR);
        DatabaseReference.goOnline();
        Log.d(TAG, "sbbbFirebaseManager initialized");
    }

    public void nextShortCode(final ShortCodeListener listener) {
        Log.d(TAG, "sbbbStarting nextShortCode operation");
        rootRef.child(KEY_NEXT_SHORT_CODE).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                Log.d(TAG, "sbbbIn doTransaction");
                Integer shortCode = currentData.getValue(Integer.class);
                if (shortCode == null) {
                    shortCode = INITIAL_SHORT_CODE - 1;
                    Log.d(TAG, "sbbbInitializing short code to: " + shortCode);
                }
                currentData.setValue(shortCode + 1);
                Log.d(TAG, "sbbbNew short code value: " + (shortCode + 1));
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                if (error != null) {
                    Log.e(TAG, "sbbbFirebase Error", error.toException());
                    listener.onError("Firebase Error: " + error.getMessage());
                } else if (!committed) {
                    Log.e(TAG, "sbbbFirebase transaction not committed");
                    listener.onError("Transaction not committed");
                } else {
                    Integer newShortCode = currentData.getValue(Integer.class);
                    Log.d(TAG, "sbbbTransaction completed. New short code: " + newShortCode);
                    listener.onShortCodeAvailable(newShortCode);
                }
            }
        });
    }

    public void storeUsingShortCode(int shortCode, String cloudAnchorId) {
        Log.d(TAG, "sbbbStoring cloud anchor ID for short code: " + shortCode);
        rootRef.child(KEY_PREFIX + shortCode).setValue(cloudAnchorId)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "sbbbCloud anchor ID stored successfully"))
                .addOnFailureListener(e -> Log.e(TAG, "sbbbFailed to store cloud anchor ID", e));
    }

    public void getCloudAnchorId(int shortCode, final CloudAnchorIdListener listener) {
        Log.d(TAG, "sbbbRetrieving cloud anchor ID for short code: " + shortCode);
        rootRef.child(KEY_PREFIX + shortCode).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                String cloudAnchorId = dataSnapshot.getValue(String.class);
                Log.d(TAG, "sbbbRetrieved cloud anchor ID: " + cloudAnchorId);
                listener.onCloudAnchorIdAvailable(cloudAnchorId);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "sbbbThe Firebase operation for getCloudAnchorId was cancelled.", error.toException());
                listener.onCloudAnchorIdAvailable(null);
            }
        });
    }
}