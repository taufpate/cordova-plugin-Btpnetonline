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
