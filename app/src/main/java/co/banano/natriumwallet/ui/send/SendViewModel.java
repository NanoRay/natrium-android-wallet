package co.banano.natriumwallet.ui.send;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import manta.MantaWallet;

public class SendViewModel extends ViewModel {
    private MutableLiveData<MantaWallet> manta = new MutableLiveData<MantaWallet>();

    public void setWallet(MantaWallet wallet) {
        manta.setValue(wallet);
    }

    public LiveData<MantaWallet> getWallet () {
        return manta;
    }


}
