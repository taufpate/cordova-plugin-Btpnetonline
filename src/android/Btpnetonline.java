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
            //final String encodedString="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACgAAAAlCAYAAAAwYKuzAAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAyFpVFh0WE1MOmNvbS5hZG9iZS54bXAAAAAAADw/eHBhY2tldCBiZWdpbj0i77u/IiBpZD0iVzVNME1wQ2VoaUh6cmVTek5UY3prYzlkIj8+IDx4OnhtcG1ldGEgeG1sbnM6eD0iYWRvYmU6bnM6bWV0YS8iIHg6eG1wdGs9IkFkb2JlIFhNUCBDb3JlIDUuNS1jMDIxIDc5LjE1NDkxMSwgMjAxMy8xMC8yOS0xMTo0NzoxNiAgICAgICAgIj4gPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4gPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIgeG1sbnM6eG1wPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvIiB4bWxuczp4bXBNTT0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wL21tLyIgeG1sbnM6c3RSZWY9Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC9zVHlwZS9SZXNvdXJjZVJlZiMiIHhtcDpDcmVhdG9yVG9vbD0iQWRvYmUgUGhvdG9zaG9wIENDIChXaW5kb3dzKSIgeG1wTU06SW5zdGFuY2VJRD0ieG1wLmlpZDo4NzNBOTRBNDg3NDkxMUU3ODM3MUQ2MUI1NjkzREFDOCIgeG1wTU06RG9jdW1lbnRJRD0ieG1wLmRpZDo4NzNBOTRBNTg3NDkxMUU3ODM3MUQ2MUI1NjkzREFDOCI+IDx4bXBNTTpEZXJpdmVkRnJvbSBzdFJlZjppbnN0YW5jZUlEPSJ4bXAuaWlkOjg3M0E5NEEyODc0OTExRTc4MzcxRDYxQjU2OTNEQUM4IiBzdFJlZjpkb2N1bWVudElEPSJ4bXAuZGlkOjg3M0E5NEEzODc0OTExRTc4MzcxRDYxQjU2OTNEQUM4Ii8+IDwvcmRmOkRlc2NyaXB0aW9uPiA8L3JkZjpSREY+IDwveDp4bXBtZXRhPiA8P3hwYWNrZXQgZW5kPSJyIj8+6sbtGQAADIlJREFUeNqcWHmMVdUZ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";
            //final String encodedString = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAHgAAABvCAYAAAAntwTxAAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAyFpVFh0WE1MOmNvbS5hZG9iZS54bXAAAAAAADw/eHBhY2tldCBiZWdpbj0i77u/IiBpZD0iVzVNME1wQ2VoaUh6cmVTek5UY3prYzlkIj8+IDx4OnhtcG1ldGEgeG1sbnM6eD0iYWRvYmU6bnM6bWV0YS8iIHg6eG1wdGs9IkFkb2JlIFhNUCBDb3JlIDUuNS1jMDIxIDc5LjE1NDkxMSwgMjAxMy8xMC8yOS0xMTo0NzoxNiAgICAgICAgIj4gPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4gPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIgeG1sbnM6eG1wTU09Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC9tbS8iIHhtbG5zOnN0UmVmPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvc1R5cGUvUmVzb3VyY2VSZWYjIiB4bWxuczp4bXA9Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC8iIHhtcE1NOkRvY3VtZW50SUQ9InhtcC5kaWQ6MEIzOTlENEE4N0ZBMTFFN0FFN0RGRTk2QkI4RDcxN0YiIHhtcE1NOkluc3RhbmNlSUQ9InhtcC5paWQ6MEIzOTlENDk4N0ZBMTFFN0FFN0RGRTk2QkI4RDcxN0YiIHhtcDpDcmVhdG9yVG9vbD0iQWRvYmUgUGhvdG9zaG9wIENDIChXaW5kb3dzKSI+IDx4bXBNTTpEZXJpdmVkRnJvbSBzdFJlZjppbnN0YW5jZUlEPSJ4bXAuaWlkOjg3M0E5NEE0ODc0OTExRTc4MzcxRDYxQjU2OTNEQUM4IiBzdFJlZjpkb2N1bWVudElEPSJ4bXAuZGlkOjg3M0E5NEE1ODc0OTExRTc4MzcxRDYxQjU2OTNEQUM4Ii8+IDwvcmRmOkRlc2NyaXB0aW9uPiA8L3JkZjpSREY+IDwveDp4bXBtZXRhPiA8P3hwYWNrZXQgZW5kPSJyIj8+Pox5AwAASRJJREFUeNrsvQdzJFeS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";
            final String encodedString = "data:image/png;base64,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";

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
