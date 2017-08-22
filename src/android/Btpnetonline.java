package cordova.plugin.Btpnetonline;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Set;
import java.util.UUID;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Bitmap.Config;
import android.util.Xml.Encoding;
import android.util.Base64;
import java.util.ArrayList;
import java.util.List;

public class Btpnetonline extends CordovaPlugin {

    private static final String LOG_TAG = "Btpnetonline";
    BluetoothAdapter mBluetoothAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    Thread workerThread;
    byte[] readBuffer;
    int readBufferPosition;
    int counter;
    volatile boolean stopWorker;
    Bitmap bitmap;

    public Btpnetonline() {
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("list")) {
            listBT(callbackContext);
            return true;
        } else if (action.equals("connect")) {
            String name = args.getString(0);
            if (findBT(callbackContext, name)) {
                try {
                    connectBT(callbackContext);
                } catch (IOException e) {
                    Log.e(LOG_TAG, e.getMessage());
                    e.printStackTrace();
                }
            } else {
                callbackContext.error("Bluetooth Device Not Found: " + name);
            }
            return true;
        } else if (action.equals("disconnect")) {
            try {
                disconnectBT(callbackContext);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        } else if (action.equals("print") || action.equals("printImage")) {
            try {
                String msg = args.getString(0);
                printImage(callbackContext, msg);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        } else if (action.equals("printText")) {
            try {
                String msg = args.getString(0);
                printText(callbackContext, msg);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        } else if (action.equals("printPOSCommand")) {
            try {
                String msg = args.getString(0);
                printPOSCommand(callbackContext, hexStringToBytes(msg));
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        }
        return false;
    }

    //This will return the array list of paired bluetooth printers
    void listBT(CallbackContext callbackContext) {
        BluetoothAdapter mBluetoothAdapter = null;
        String errMsg = null;
        try {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter == null) {
                errMsg = "No bluetooth adapter available";
                Log.e(LOG_TAG, errMsg);
                callbackContext.error(errMsg);
                return;
            }
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                this.cordova.getActivity().startActivityForResult(enableBluetooth, 0);
            }
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                JSONArray json = new JSONArray();
                for (BluetoothDevice device : pairedDevices) {
                    /*
                     Hashtable map = new Hashtable();
                     map.put("type", device.getType());
                     map.put("address", device.getAddress());
                     map.put("name", device.getName());
                     JSONObject jObj = new JSONObject(map);
                     */
                    json.put(device.getName());
                }
                callbackContext.success(json);
            } else {
                callbackContext.error("No Bluetooth Device Found");
            }
            //Log.d(LOG_TAG, "Bluetooth Device Found: " + mmDevice.getName());
        } catch (Exception e) {
            errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
    }

    // This will find a bluetooth printer device
    boolean findBT(CallbackContext callbackContext, String name) {
        try {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter == null) {
                Log.e(LOG_TAG, "No bluetooth adapter available");
            }
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                this.cordova.getActivity().startActivityForResult(enableBluetooth, 0);
            }
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getName().equalsIgnoreCase(name)) {
                        mmDevice = device;
                        return true;
                    }
                }
            }
            Log.d(LOG_TAG, "Bluetooth Device Found: " + mmDevice.getName());
        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    // Tries to open a connection to the bluetooth printer device
    boolean connectBT(CallbackContext callbackContext) throws IOException {
        try {
            // Standard SerialPortService ID
            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
            mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
            mmSocket.connect();
            mmOutputStream = mmSocket.getOutputStream();
            mmInputStream = mmSocket.getInputStream();
            beginListenForData();
            //Log.d(LOG_TAG, "Bluetooth Opened: " + mmDevice.getName());
            callbackContext.success("Bluetooth Opened: " + mmDevice.getName());
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    // After opening a connection to bluetooth printer device,
    // we have to listen and check if a data were sent to be printed.
    void beginListenForData() {
        try {
            final Handler handler = new Handler();
            // This is the ASCII code for a newline character
            final byte delimiter = 10;
            stopWorker = false;
            readBufferPosition = 0;
            readBuffer = new byte[1024];
            workerThread = new Thread(new Runnable() {
                public void run() {
                    while (!Thread.currentThread().isInterrupted() && !stopWorker) {
                        try {
                            int bytesAvailable = mmInputStream.available();
                            if (bytesAvailable > 0) {
                                byte[] packetBytes = new byte[bytesAvailable];
                                mmInputStream.read(packetBytes);
                                for (int i = 0; i < bytesAvailable; i++) {
                                    byte b = packetBytes[i];
                                    if (b == delimiter) {
                                        byte[] encodedBytes = new byte[readBufferPosition];
                                        System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                        /*
                                         final String data = new String(encodedBytes, "US-ASCII");
                                         readBufferPosition = 0;
                                         handler.post(new Runnable() {
                                         public void run() {
                                         myLabel.setText(data);
                                         }
                                         });
                                         */
                                    } else {
                                        readBuffer[readBufferPosition++] = b;
                                    }
                                }
                            }
                        } catch (IOException ex) {
                            stopWorker = true;
                        }
                    }
                }
            });
            workerThread.start();
        } catch (NullPointerException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //This will send data to bluetooth printer
    boolean printText(CallbackContext callbackContext, String msg) throws IOException {
        try {
            mmOutputStream.write(msg.getBytes());
            // tell the user data were sent
            //Log.d(LOG_TAG, "Data Sent");
            callbackContext.success("Data Sent");
            return true;

        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    //This will send data to bluetooth printer
    boolean printImage(CallbackContext callbackContext, String msg) throws IOException {
        try {

            //final String encodedString = msg;
            final String encodedString="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACgAAAAlCAYAAAAwYKuzAAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAyFpVFh0WE1MOmNvbS5hZG9iZS54bXAAAAAAADw/eHBhY2tldCBiZWdpbj0i77u/IiBpZD0iVzVNME1wQ2VoaUh6cmVTek5UY3prYzlkIj8+IDx4OnhtcG1ldGEgeG1sbnM6eD0iYWRvYmU6bnM6bWV0YS8iIHg6eG1wdGs9IkFkb2JlIFhNUCBDb3JlIDUuNS1jMDIxIDc5LjE1NDkxMSwgMjAxMy8xMC8yOS0xMTo0NzoxNiAgICAgICAgIj4gPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4gPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIgeG1sbnM6eG1wPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvIiB4bWxuczp4bXBNTT0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wL21tLyIgeG1sbnM6c3RSZWY9Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC9zVHlwZS9SZXNvdXJjZVJlZiMiIHhtcDpDcmVhdG9yVG9vbD0iQWRvYmUgUGhvdG9zaG9wIENDIChXaW5kb3dzKSIgeG1wTU06SW5zdGFuY2VJRD0ieG1wLmlpZDo4NzNBOTRBNDg3NDkxMUU3ODM3MUQ2MUI1NjkzREFDOCIgeG1wTU06RG9jdW1lbnRJRD0ieG1wLmRpZDo4NzNBOTRBNTg3NDkxMUU3ODM3MUQ2MUI1NjkzREFDOCI+IDx4bXBNTTpEZXJpdmVkRnJvbSBzdFJlZjppbnN0YW5jZUlEPSJ4bXAuaWlkOjg3M0E5NEEyODc0OTExRTc4MzcxRDYxQjU2OTNEQUM4IiBzdFJlZjpkb2N1bWVudElEPSJ4bXAuZGlkOjg3M0E5NEEzODc0OTExRTc4MzcxRDYxQjU2OTNEQUM4Ii8+IDwvcmRmOkRlc2NyaXB0aW9uPiA8L3JkZjpSREY+IDwveDp4bXBtZXRhPiA8P3hwYWNrZXQgZW5kPSJyIj8+6sbtGQAADIlJREFUeNqcWHmMVdUZ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";

            final String pureBase64Encoded = encodedString.substring(encodedString.indexOf(",") + 1);
            final byte[] decodedBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT);

            Bitmap decodedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

            bitmap = decodedBitmap;
            int mWidth = bitmap.getWidth();
            int mHeight = bitmap.getHeight();

            bitmap = resizeImage(bitmap, 48 * 8, mHeight);

            byte[] bt = decodeBitmap(bitmap);

            mmOutputStream.write(bt);
            // tell the user data were sent
            //Log.d(LOG_TAG, "Data Sent");
            callbackContext.success("Data Sent");
            return true;

        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    boolean printPOSCommand(CallbackContext callbackContext, byte[] buffer) throws IOException {
        try {
            mmOutputStream.write(buffer);
            // tell the user data were sent
            Log.d(LOG_TAG, "Data Sent");
            callbackContext.success("Data Sent");
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    // disconnect bluetooth printer.
    boolean disconnectBT(CallbackContext callbackContext) throws IOException {
        try {
            stopWorker = true;
            mmOutputStream.close();
            mmInputStream.close();
            mmSocket.close();
            callbackContext.success("Bluetooth Disconnect");
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage();
            Log.e(LOG_TAG, errMsg);
            e.printStackTrace();
            callbackContext.error(errMsg);
        }
        return false;
    }

    //New implementation, change old
    public static byte[] hexStringToBytes(String hexString) {
        if (hexString == null || hexString.equals("")) {
            return null;
        }
        hexString = hexString.toUpperCase();
        int length = hexString.length() / 2;
        char[] hexChars = hexString.toCharArray();
        byte[] d = new byte[length];
        for (int i = 0; i < length; i++) {
            int pos = i * 2;
            d[i] = (byte) (charToByte(hexChars[pos]) << 4 | charToByte(hexChars[pos + 1]));
        }
        return d;
    }

    private static byte charToByte(char c) {
        return (byte) "0123456789ABCDEF".indexOf(c);
    }

    //New implementation
    private static Bitmap resizeImage(Bitmap bitmap, int w, int h) {
        Bitmap BitmapOrg = bitmap;
        int width = BitmapOrg.getWidth();
        int height = BitmapOrg.getHeight();

        if (width > w) {
            float scaleWidth = ((float) w) / width;
            float scaleHeight = ((float) h) / height + 24;
            Matrix matrix = new Matrix();
            matrix.postScale(scaleWidth, scaleWidth);
            Bitmap resizedBitmap = Bitmap.createBitmap(BitmapOrg, 0, 0, width,
                    height, matrix, true);
            return resizedBitmap;
        } else {
            Bitmap resizedBitmap = Bitmap.createBitmap(w, height + 24, Config.RGB_565);
            Canvas canvas = new Canvas(resizedBitmap);
            Paint paint = new Paint();
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(bitmap, (w - width) / 2, 0, paint);
            return resizedBitmap;
        }
    }

    private static String hexStr = "0123456789ABCDEF";

    private static String[] binaryArray = {"0000", "0001", "0010", "0011",
        "0100", "0101", "0110", "0111", "1000", "1001", "1010", "1011",
        "1100", "1101", "1110", "1111"};

    public static byte[] decodeBitmap(Bitmap bmp) {
        int bmpWidth = bmp.getWidth();
        int bmpHeight = bmp.getHeight();
        List<String> list = new ArrayList<String>(); //binaryString list
        StringBuffer sb;
        int bitLen = bmpWidth / 8;
        int zeroCount = bmpWidth % 8;
        String zeroStr = "";
        if (zeroCount > 0) {
            bitLen = bmpWidth / 8 + 1;
            for (int i = 0; i < (8 - zeroCount); i++) {
                zeroStr = zeroStr + "0";
            }
        }

        for (int i = 0; i < bmpHeight; i++) {
            sb = new StringBuffer();
            for (int j = 0; j < bmpWidth; j++) {
                int color = bmp.getPixel(j, i);

                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                // if color close to white，bit='0', else bit='1'
                if (r > 160 && g > 160 && b > 160) {
                    sb.append("0");
                } else {
                    sb.append("1");
                }
            }
            if (zeroCount > 0) {
                sb.append(zeroStr);
            }
            list.add(sb.toString());
        }

        List<String> bmpHexList = binaryListToHexStringList(list);
        String commandHexString = "1D763000";
        String widthHexString = Integer.toHexString(bmpWidth % 8 == 0 ? bmpWidth / 8 : (bmpWidth / 8 + 1));
        if (widthHexString.length() > 2) {
            Log.d(LOG_TAG, "DECODEBITMAP ERROR : width is too large");
            return null;
        } else if (widthHexString.length() == 1) {
            widthHexString = "0" + widthHexString;
        }
        widthHexString = widthHexString + "00";

        String heightHexString = Integer.toHexString(bmpHeight);
        if (heightHexString.length() > 2) {
            Log.d(LOG_TAG, "DECODEBITMAP ERROR : height is too large");
            return null;
        } else if (heightHexString.length() == 1) {
            heightHexString = "0" + heightHexString;
        }
        heightHexString = heightHexString + "00";

        List<String> commandList = new ArrayList<String>();
        commandList.add(commandHexString + widthHexString + heightHexString);
        commandList.addAll(bmpHexList);

        return hexList2Byte(commandList);
    }

    public static List<String> binaryListToHexStringList(List<String> list) {
        List<String> hexList = new ArrayList<String>();
        for (String binaryStr : list) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < binaryStr.length(); i += 8) {
                String str = binaryStr.substring(i, i + 8);

                String hexString = myBinaryStrToHexString(str);
                sb.append(hexString);
            }
            hexList.add(sb.toString());
        }
        return hexList;

    }

    public static String myBinaryStrToHexString(String binaryStr) {
        String hex = "";
        String f4 = binaryStr.substring(0, 4);
        String b4 = binaryStr.substring(4, 8);
        for (int i = 0; i < binaryArray.length; i++) {
            if (f4.equals(binaryArray[i])) {
                hex += hexStr.substring(i, i + 1);
            }
        }
        for (int i = 0; i < binaryArray.length; i++) {
            if (b4.equals(binaryArray[i])) {
                hex += hexStr.substring(i, i + 1);
            }
        }

        return hex;
    }

    public static byte[] hexList2Byte(List<String> list) {
        List<byte[]> commandList = new ArrayList<byte[]>();

        for (String hexStr : list) {
            commandList.add(hexStringToBytes(hexStr));
        }
        byte[] bytes = sysCopy(commandList);
        return bytes;
    }

    public static byte[] sysCopy(List<byte[]> srcArrays) {
        int len = 0;
        for (byte[] srcArray : srcArrays) {
            len += srcArray.length;
        }
        byte[] destArray = new byte[len];
        int destLen = 0;
        for (byte[] srcArray : srcArrays) {
            System.arraycopy(srcArray, 0, destArray, destLen, srcArray.length);
            destLen += srcArray.length;
        }
        return destArray;
    }

}
