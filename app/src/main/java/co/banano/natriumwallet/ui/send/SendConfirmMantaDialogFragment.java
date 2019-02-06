package co.banano.natriumwallet.ui.send;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.ajalt.reprint.core.AuthenticationFailureReason;
import com.github.ajalt.reprint.core.Reprint;
import com.google.android.material.snackbar.Snackbar;
import com.hwangjr.rxbus.annotation.Subscribe;

import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import co.banano.natriumwallet.R;
import co.banano.natriumwallet.bus.CreatePin;
import co.banano.natriumwallet.bus.PinComplete;
import co.banano.natriumwallet.bus.RxBus;
import co.banano.natriumwallet.bus.SocketError;
import co.banano.natriumwallet.databinding.FragmentSendConfirmBinding;
import co.banano.natriumwallet.databinding.FragmentSendConfirmMantaBinding;
import co.banano.natriumwallet.model.Address;
import co.banano.natriumwallet.model.AuthMethod;
import co.banano.natriumwallet.model.Contact;
import co.banano.natriumwallet.model.Credentials;
import co.banano.natriumwallet.model.KaliumWallet;
import co.banano.natriumwallet.network.AccountService;
import co.banano.natriumwallet.network.model.response.ErrorResponse;
import co.banano.natriumwallet.network.model.response.ProcessResponse;
import co.banano.natriumwallet.ui.common.ActivityWithComponent;
import co.banano.natriumwallet.ui.common.BaseDialogFragment;
import co.banano.natriumwallet.ui.common.UIUtil;
import co.banano.natriumwallet.ui.common.WindowControl;
import co.banano.natriumwallet.util.NumberUtil;
import co.banano.natriumwallet.util.SharedPreferencesUtil;
import io.realm.Realm;
import manta.MantaWallet;
import manta.PaymentRequestEnvelope;
import manta.PaymentRequestMessage;
import timber.log.Timber;

/**
 * Send confirm screen
 */
public class SendConfirmMantaDialogFragment extends BaseDialogFragment {
    public static String TAG = SendConfirmMantaDialogFragment.class.getSimpleName();
    @Inject
    KaliumWallet wallet;
    @Inject
    AccountService accountService;
    @Inject
    SharedPreferencesUtil sharedPreferencesUtil;
    @Inject
    Realm realm;

    private FragmentSendConfirmMantaBinding binding;
    private Address address;
    private AlertDialog fingerprintDialog;
    private Activity mActivity;
    private Fragment mTargetFragment;
    private int retryCount = 0;

    private MantaWallet manta;
    private PaymentRequestEnvelope envelope;

    /**
     * Create new instance of the dialog fragment (handy pattern if any data needs to be passed to it)
     *
     * @return SendConfirmDialogFragment instance
     */
    public static SendConfirmMantaDialogFragment newInstance(String url) {
        Bundle args = new Bundle();

        args.putString("manta_url", url);

        SendConfirmMantaDialogFragment fragment = new SendConfirmMantaDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_FRAME, R.style.AppTheme_Modal);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // init dependency injection
        retryCount = 0;
        mActivity = getActivity();
        mTargetFragment = getTargetFragment();
        if (mActivity instanceof ActivityWithComponent) {
            ((ActivityWithComponent) mActivity).getActivityComponent().inject(this);
        }


        // subscribe to bus
        RxBus.get().register(this);


        // inflate the view
        binding = DataBindingUtil.inflate(
                inflater, R.layout.fragment_send_confirm_manta, container, false);
        view = binding.getRoot();
        binding.setHandlers(new ClickHandlers());

        // Restrict height
        Window window = getDialog().getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, UIUtil.getDialogHeight(false, getContext()));
        window.setGravity(Gravity.BOTTOM);

        // Lottie hardware acceleration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            binding.animationView.useHardwareAcceleration(true);
        }

        return view;
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Load Manta Request

        String manta_url = getArguments().getString("manta_url");

        manta = MantaWallet.Companion.factory(manta_url, new MemoryPersistence(), null);

        assert manta != null;

        showLoadingOverlay();

        manta.getPaymentRequestAsync("NANO").thenApply((envelope) -> {
                    this.envelope = envelope;
                    view.post(initFields);
                    return envelope;
                }
        );

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // unregister from bus
        if (fingerprintDialog != null) {
            fingerprintDialog.dismiss();
        }
        RxBus.get().unregister(this);
    }

    private Runnable initFields = new Runnable() {

        @Override
        public void run() {
            hideLoadingOverlay();
            if (envelope == null) {
                showMantaError();
            } else {
                PaymentRequestMessage paymentRequest = envelope.unpack();

                if (paymentRequest != null) {

                    hideMantaLoading();

                    String nanoAmount = paymentRequest.getDestinations().get(0).getAmount().toString();

                    // Set send amount
                    wallet.setSendNanoAmount(nanoAmount);

                    // Set address

                    address = new Address(paymentRequest.getDestinations().get(0).getDestinationAddress());

                    if (binding != null) {
                        binding.destinationAddress.setText(paymentRequest.getDestinations().get(0).getDestinationAddress());
                        binding.merchantAddress.setText(paymentRequest.getMerchant().getAddress());
                        binding.merchantName.setText(paymentRequest.getMerchant().getName());
                        binding.fiatAmount.setText(paymentRequest.getAmount().toString());
                        binding.nanoAmount.setText(wallet.getSendNanoAmount());
                        binding.sendCancel.setEnabled(true);
                        binding.sendConfirm.setEnabled(true);
                    }

                }
            }

        }
    };

    private void showMantaError() {
        binding.mantaLoading.setText("Manta Timeout Error");
    }

    private void hideMantaLoading() {

        if (binding != null && binding.mantaErrorOverlay != null) {
            animateView(binding.mantaErrorOverlay, View.GONE, 0, 200);
        }
    }

    private void showLoadingOverlay() {
        if (binding != null && binding.progressOverlay != null) {
            binding.sendCancel.setEnabled(false);
            binding.sendConfirm.setEnabled(false);
            animateView(binding.progressOverlay, View.VISIBLE, 1, 200);
        }
    }

    private void hideLoadingOverlay() {
        if (binding != null && binding.progressOverlay != null) {
            animateView(binding.progressOverlay, View.GONE, 0, 200);
        }
    }

    @SuppressLint("StringFormatInvalid")
    private void showFingerprintDialog(View view) {
        int style = Build.VERSION.SDK_INT >= 21 ? R.style.AlertDialogCustom : android.R.style.Theme_Holo_Dialog;
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), style);
        builder.setMessage(getString(R.string.send_fingerprint_description,
                !wallet.getSendNanoAmountFormatted().isEmpty() ? wallet.getSendNanoAmountFormatted() : "0"));
        builder.setView(view);
        SpannableString negativeText = new SpannableString(getString(android.R.string.cancel));
        negativeText.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.ltblue)), 0, negativeText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setNegativeButton(negativeText, (dialog, which) -> Reprint.cancelAuthentication());

        fingerprintDialog = builder.create();
        fingerprintDialog.setCanceledOnTouchOutside(true);
        // display dialog
        fingerprintDialog.show();
    }

    private void showFingerprintError(AuthenticationFailureReason reason, CharSequence message, View view) {
        if (isAdded()) {
            final HashMap<String, String> customData = new HashMap<>();
            customData.put("description", reason.name());
            TextView textView = view.findViewById(R.id.fingerprint_textview);
            textView.setText(message.toString());
            if (getContext() != null) {
                textView.setTextColor(ContextCompat.getColor(getContext(), R.color.error));
            }
            textView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_fingerprint_error, 0, 0, 0);
        }
    }

    private void executeSend() {
        retryCount++;
        showLoadingOverlay();
        BigInteger sendAmount;

        sendAmount = NumberUtil.getAmountAsRawBigInteger(wallet.getSendNanoAmount());


        accountService.requestSend(wallet.getFrontierBlock(), address, sendAmount);
    }

    private boolean checkBalance() {
        BigInteger sendAmount = NumberUtil.getAmountAsRawBigInteger(wallet.getSendNanoAmount());

        if (new BigDecimal(wallet.getSendNanoAmount()).compareTo(new BigDecimal(wallet.getAccountBalanceBananoNoComma())) > 0) {
            showAmountError(R.string.send_insufficient_balance);
            return false;
        }

        if (wallet.getFrontierBlock() == null) {
            showAmountError(R.string.send_no_frontier);
            return false;
        }

        return true;
    }

    private void showAmountError(int str_id) {
        Toast toast = Toast.makeText(getContext(), str_id, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 0);
        toast.show();
    }

    /**
     * Catch errors from the service
     *
     * @param errorResponse Error Response event
     */
    @Subscribe
    public void receiveServiceError(ErrorResponse errorResponse) {
        hideLoadingOverlay();
        if (mTargetFragment != null) {
            mTargetFragment.onActivityResult(getTargetRequestCode(), SEND_FAILED, mActivity.getIntent());
        }
        dismiss();
    }

    /**
     * Catch service error
     *
     * @param socketError Socket error event
     */
    @Subscribe
    public void receiveSocketError(SocketError socketError) {
        if (retryCount > 1) {
            hideLoadingOverlay();
            if (mTargetFragment != null) {
                mTargetFragment.onActivityResult(getTargetRequestCode(), SEND_FAILED, mActivity.getIntent());
            }
            dismiss();
        } else {
            executeSend();
        }
    }

    /**
     * Received a successful send response so go back
     *
     * @param processResponse Process Response
     */
    @Subscribe
    public void receiveProcessResponse(ProcessResponse processResponse) {
        if (manta != null) {
            manta.sendPayment(processResponse.getHash(), "NANO");
        }

        hideLoadingOverlay();
        if (mTargetFragment != null) {
            Bundle conData = new Bundle();
            conData.putString("destination", this.binding.merchantName.getText().toString());
            Intent intent = mActivity.getIntent();
            intent.putExtras(conData);
            mTargetFragment.onActivityResult(getTargetRequestCode(), SEND_COMPLETE, mActivity.getIntent());
        }
        dismiss();
        accountService.requestUpdate();
    }

    /**
     * Pin entered correctly
     *
     * @param pinComplete PinComplete object
     */
    @Subscribe
    public void receivePinComplete(PinComplete pinComplete) {
        executeSend();
    }

    @Subscribe
    public void receiveCreatePin(CreatePin pinComplete) {
        realm.executeTransaction(realm -> {
            Credentials credentials = realm.where(Credentials.class).findFirst();
            if (credentials != null) {
                credentials.setPin(pinComplete.getPin());
            }
        });
        executeSend();
    }

    public class ClickHandlers {
        public void onClickClose(View view) {
            if (mTargetFragment != null) {
                mTargetFragment.onActivityResult(getTargetRequestCode(), SEND_CANCELED, mActivity.getIntent());
            }
            dismiss();
        }

        @SuppressLint("StringFormatInvalid")
        public void onClickConfirm(View view) {
            if (!checkBalance()) return;

            Credentials credentials = realm.where(Credentials.class).findFirst();

            if (Reprint.isHardwarePresent() && Reprint.hasFingerprintRegistered() && sharedPreferencesUtil.getAuthMethod() == AuthMethod.FINGERPRINT) {
                // show fingerprint dialog
                LayoutInflater factory = LayoutInflater.from(getContext());
                @SuppressLint("InflateParams") final View viewFingerprint = factory.inflate(R.layout.view_fingerprint, null);
                showFingerprintDialog(viewFingerprint);
                com.github.ajalt.reprint.rxjava2.RxReprint.authenticate()
                        .subscribe(result -> {
                            switch (result.status) {
                                case SUCCESS:
                                    fingerprintDialog.dismiss();
                                    executeSend();
                                    break;
                                case NONFATAL_FAILURE:
                                    showFingerprintError(result.failureReason, result.errorMessage, viewFingerprint);
                                    break;
                                case FATAL_FAILURE:
                                    showFingerprintError(result.failureReason, result.errorMessage, viewFingerprint);
                                    break;
                            }
                        });
            } else if (credentials != null && credentials.getPin() != null) {
                showPinScreen(getString(R.string.send_pin_description, wallet.getSendNanoAmount()));
            } else if (credentials != null && credentials.getPin() == null) {
                showCreatePinScreen();
            }
        }
    }
}
