package org.sykes.metaltnt.client;
import net.fabricmc.api.ClientModInitializer;
import org.sykes.metaltnt.client.checker.CheckifMac;


public class MetaltntClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (CheckifMac.isMac()){
        System.out.println("Running on a Mac system.");

        //File does not really do much outside of log its working
    } else {
        System.out.println("Not running on a Mac system.");
    }
    }
}