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

            final String encodedString = msg;
            //final String encodedString="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACgAAAAlCAYAAAAwYKuzAAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAyFpVFh0WE1MOmNvbS5hZG9iZS54bXAAAAAAADw/eHBhY2tldCBiZWdpbj0i77u/IiBpZD0iVzVNME1wQ2VoaUh6cmVTek5UY3prYzlkIj8+IDx4OnhtcG1ldGEgeG1sbnM6eD0iYWRvYmU6bnM6bWV0YS8iIHg6eG1wdGs9IkFkb2JlIFhNUCBDb3JlIDUuNS1jMDIxIDc5LjE1NDkxMSwgMjAxMy8xMC8yOS0xMTo0NzoxNiAgICAgICAgIj4gPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4gPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIgeG1sbnM6eG1wPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvIiB4bWxuczp4bXBNTT0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wL21tLyIgeG1sbnM6c3RSZWY9Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC9zVHlwZS9SZXNvdXJjZVJlZiMiIHhtcDpDcmVhdG9yVG9vbD0iQWRvYmUgUGhvdG9zaG9wIENDIChXaW5kb3dzKSIgeG1wTU06SW5zdGFuY2VJRD0ieG1wLmlpZDo4NzNBOTRBNDg3NDkxMUU3ODM3MUQ2MUI1NjkzREFDOCIgeG1wTU06RG9jdW1lbnRJRD0ieG1wLmRpZDo4NzNBOTRBNTg3NDkxMUU3ODM3MUQ2MUI1NjkzREFDOCI+IDx4bXBNTTpEZXJpdmVkRnJvbSBzdFJlZjppbnN0YW5jZUlEPSJ4bXAuaWlkOjg3M0E5NEEyODc0OTExRTc4MzcxRDYxQjU2OTNEQUM4IiBzdFJlZjpkb2N1bWVudElEPSJ4bXAuZGlkOjg3M0E5NEEzODc0OTExRTc4MzcxRDYxQjU2OTNEQUM4Ii8+IDwvcmRmOkRlc2NyaXB0aW9uPiA8L3JkZjpSREY+IDwveDp4bXBtZXRhPiA8P3hwYWNrZXQgZW5kPSJyIj8+6sbtGQAADIlJREFUeNqcWHmMVdUZ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";
            //final String encodedString = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAHgAAABvCAYAAAAntwTxAAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAyFpVFh0WE1MOmNvbS5hZG9iZS54bXAAAAAAADw/eHBhY2tldCBiZWdpbj0i77u/IiBpZD0iVzVNME1wQ2VoaUh6cmVTek5UY3prYzlkIj8+IDx4OnhtcG1ldGEgeG1sbnM6eD0iYWRvYmU6bnM6bWV0YS8iIHg6eG1wdGs9IkFkb2JlIFhNUCBDb3JlIDUuNS1jMDIxIDc5LjE1NDkxMSwgMjAxMy8xMC8yOS0xMTo0NzoxNiAgICAgICAgIj4gPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4gPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIgeG1sbnM6eG1wTU09Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC9tbS8iIHhtbG5zOnN0UmVmPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvc1R5cGUvUmVzb3VyY2VSZWYjIiB4bWxuczp4bXA9Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC8iIHhtcE1NOkRvY3VtZW50SUQ9InhtcC5kaWQ6MEIzOTlENEE4N0ZBMTFFN0FFN0RGRTk2QkI4RDcxN0YiIHhtcE1NOkluc3RhbmNlSUQ9InhtcC5paWQ6MEIzOTlENDk4N0ZBMTFFN0FFN0RGRTk2QkI4RDcxN0YiIHhtcDpDcmVhdG9yVG9vbD0iQWRvYmUgUGhvdG9zaG9wIENDIChXaW5kb3dzKSI+IDx4bXBNTTpEZXJpdmVkRnJvbSBzdFJlZjppbnN0YW5jZUlEPSJ4bXAuaWlkOjg3M0E5NEE0ODc0OTExRTc4MzcxRDYxQjU2OTNEQUM4IiBzdFJlZjpkb2N1bWVudElEPSJ4bXAuZGlkOjg3M0E5NEE1ODc0OTExRTc4MzcxRDYxQjU2OTNEQUM4Ii8+IDwvcmRmOkRlc2NyaXB0aW9uPiA8L3JkZjpSREY+IDwveDp4bXBtZXRhPiA8P3hwYWNrZXQgZW5kPSJyIj8+Pox5AwAASRJJREFUeNrsvQdzJFeS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";
            //final String encodedString = "data:image/png;base64,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";

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
