package org.zywx.wbpalmstar.plugin.uexdownloadermgr;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.params.ClientPNames;
import org.json.JSONException;
import org.json.JSONObject;
import org.zywx.wbpalmstar.base.BUtility;
import org.zywx.wbpalmstar.base.ResoureFinder;
import org.zywx.wbpalmstar.engine.EBrowserView;
import org.zywx.wbpalmstar.engine.universalex.EUExBase;
import org.zywx.wbpalmstar.engine.universalex.EUExCallback;
import org.zywx.wbpalmstar.engine.universalex.EUExUtil;
import org.zywx.wbpalmstar.platform.certificates.Http;
import org.zywx.wbpalmstar.widgetone.dataservice.WWidgetData;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class EUExDownloaderMgr extends EUExBase {

    public final static String KEY_APPVERIFY = "appverify";
    public final static String XMAS_APPID = "x-mas-app-id";
    public static final String tag = "uexDownloaderMgr_";
    private static final String F_CALLBACK_NAME_DOWNLOADPERCENT = "uexDownloaderMgr.onStatus";
    private static final String F_CALLBACK_NAME_CREATEDOWNLOADER = "uexDownloaderMgr.cbCreateDownloader";
    private static final String F_CALLBACK_NAME_GETINFO = "uexDownloaderMgr.cbGetInfo";

    public final static int F_STATE_CREATE_DOWNLOADER = 0;
    public final static int F_STATE_DOWNLOADING = 1;
    public static final String F_CREATETABLE_SQL = "CREATE TABLE IF  NOT EXISTS Downloader(_id INTEGER PRIMARY KEY,url TEXT,filePath TEXT,fileSize TEXT,downSize TEXT,time TEXT)";
    private DatabaseHelper m_databaseHelper = null;
    private SQLiteDatabase m_database = null;
    static final String SCRIPT_HEADER = "javascript:";

    private HashMap<Integer, DownLoadAsyncTask> m_objectMap;
    private HashMap<String, String> url_objectMap, headersMap;
    Context m_context;
    private String mCertPassword = "";
    private String mCertPath = "";
    private boolean mHasCert = false;

    private WWidgetData mCurWData;
    private String lastPercent = "";
    private long mLastTime=0;

    public EUExDownloaderMgr(Context context, EBrowserView view) {
        super(context, view);
        m_objectMap = new HashMap<Integer, EUExDownloaderMgr.DownLoadAsyncTask>();
        url_objectMap = new HashMap<String, String>();
        headersMap = new HashMap<String, String>();
        m_context = context;
        mCurWData = getWidgetData(view);
    }

    private void creatTaskTable() {
        if (m_databaseHelper != null) {
            return;
        }
        m_databaseHelper = new DatabaseHelper(mContext, "plugin_downloadermgr_downloader.db", 1);
        m_database = m_databaseHelper.getReadableDatabase();
        m_database.execSQL(F_CREATETABLE_SQL);
    }

    private void addTaskToDB(String url, String filePath, long fileSize) {
        if (selectTaskFromDB(url) == null) {
            String sql = "INSERT INTO Downloader (url,filePath,fileSize,downSize,time) VALUES ('"
                    + url
                    + "','"
                    + filePath
                    + "','"
                    + fileSize
                    + "','0','"
                    + getNowTime() + "')";
            if (m_database == null) {
                creatTaskTable();
            }
            m_database.execSQL(sql);
        }
    }

    private void updateTaskFromDB(String url, long downSize) {
        String sql = "UPDATE Downloader SET time = '" + getNowTime()
                + "',downSize ='" + downSize + "'  WHERE url = '" + url + "'";
        if (m_database == null) {
            creatTaskTable();
        }
        m_database.execSQL(sql);
    }

    private String[] selectTaskFromDB(String url) {
        String sql = "SELECT * FROM Downloader WHERE url = '" + url + "'";
        if (m_database == null) {
            creatTaskTable();
        }
        Cursor cursor = m_database.rawQuery(sql, null);
        if (cursor.moveToNext()) {
            String[] reslt = new String[4];
            reslt[0] = cursor.getString(2);
            reslt[1] = cursor.getString(3);
            reslt[2] = cursor.getString(4);
            reslt[3] = cursor.getString(5);

            return reslt;
        } else {
            return null;
        }
    }

    private void deleteTaskFromDB(String url) throws SQLException {
        String sql = "DELETE FROM Downloader WHERE url = '" + url + "'";
        if (m_database == null) {
            creatTaskTable();
        }
        m_database.execSQL(sql);
    }

    public void createDownloader(String[] parm) {
        if (parm.length != 1) {
            return;
        }
        String inOpCode = parm[0];
        if (!BUtility.isNumeric(inOpCode)) {
            return;
        }
        checkAppStatus(mContext, mBrwView.getRootWidget().m_appId);
        if (m_objectMap.containsKey(Integer.parseInt(inOpCode))) {
            jsCallback(F_CALLBACK_NAME_CREATEDOWNLOADER,
                    Integer.parseInt(inOpCode), EUExCallback.F_C_INT,
                    EUExCallback.F_C_FAILED);
            return;
        }
        creatTaskTable();
        DownLoadAsyncTask dlTask = new DownLoadAsyncTask();
        m_objectMap.put(Integer.parseInt(inOpCode), dlTask);
        jsCallback(F_CALLBACK_NAME_CREATEDOWNLOADER,
                Integer.parseInt(inOpCode), EUExCallback.F_C_INT,
                EUExCallback.F_C_SUCCESS);
    }

    /**
     * 下载
     *
     */
    public void download(String[] parm) {
        if (parm.length != 4) {
            return;
        }
        String inOpCode = parm[0], inDLUrl = parm[1], inSavePath = parm[2], inMode = parm[3];

        url_objectMap.put(inDLUrl, inOpCode);
        if (TextUtils.isEmpty(inDLUrl)) {
            inDLUrl = parm[1];
        }
        if (!BUtility.isNumeric(inOpCode)) {
            return;
        }
        inSavePath = BUtility.makeUrl(mBrwView.getCurrentUrl(), inSavePath);
        if (inSavePath == null || inSavePath.length() == 0) {
            errorCallback(
                    Integer.parseInt(inOpCode),
                    EUExCallback.F_E_UEXDOWNLOAD_DOWNLOAD_1,
                    ResoureFinder.getInstance().getString(mContext,
                            "plugin_downloadermgr_error_parameter"));
            return;
        }
        inSavePath = BUtility.makeRealPath(
                BUtility.makeUrl(mBrwView.getCurrentUrl(), inSavePath),
                mBrwView.getCurrentWidget().m_widgetPath,
                mBrwView.getCurrentWidget().m_wgtType);
        DownLoadAsyncTask dlTask = m_objectMap.get(Integer.parseInt(inOpCode));
        if (dlTask != null) {
            if (dlTask.state == F_STATE_CREATE_DOWNLOADER) {
                dlTask.state = F_STATE_DOWNLOADING;
                dlTask.execute(inDLUrl, inSavePath, inMode,
                        String.valueOf(inOpCode));

            }

        } else {
            errorCallback(
                    Integer.parseInt(inOpCode),
                    EUExCallback.F_E_UEXDOWNLOAD_DOWNLOAD_1,
                    ResoureFinder.getInstance().getString(mContext,
                            "plugin_downloadermgr_error_parameter"));

        }
    }

    private void cbToJs(int inOpCode, Long fileSize, String percent, int status) {
        long currentTime=System.currentTimeMillis();
        String js = SCRIPT_HEADER + "if("
                + F_CALLBACK_NAME_DOWNLOADPERCENT + "){"
                + F_CALLBACK_NAME_DOWNLOADPERCENT + "("
                + inOpCode + "," + fileSize + "," + percent + "," + status + ")}";
        if((!percent.equals(lastPercent)&&currentTime-mLastTime>500)
                || (EUExCallback.F_C_DownLoading != status))
            {
                mLastTime=currentTime;
                lastPercent = percent;
                onCallback(js);
            }
    }

    public void closeDownloader(String[] parm) {
        if (parm.length != 1) {
            return;
        }
        String inOpCode = parm[0];
        if (!BUtility.isNumeric(inOpCode)) {
            return;
        }
        DownLoadAsyncTask dlTask = m_objectMap.remove(Integer
                .parseInt(inOpCode));
        if (dlTask != null) {
            dlTask.cancel(true);
            dlTask = null;

        }

    }

    public void getInfo(String[] parm) {
        if (parm.length != 1) {
            return;
        }
        String inUrl = parm[0];
        String[] info = selectTaskFromDB(inUrl);
        JSONObject json = new JSONObject();
        if (info != null) {

            try {
                json.put("savePath", info[0]);
                json.put("fileSize", info[1]);
                json.put("currentSize", info[2]);
                json.put("lastTime", info[3]);
            } catch (JSONException e) {
                e.printStackTrace();
            }

            jsCallback(F_CALLBACK_NAME_GETINFO, 0, EUExCallback.F_C_JSON,
                    json.toString());
        } else {
            jsCallback(F_CALLBACK_NAME_GETINFO, 0, EUExCallback.F_C_JSON, "");
        }

    }

    public void clearTask(String[] parm) {
        boolean isDelete = false;
        String inUrl = null;
        if (parm.length == 1) {
            inUrl = parm[0];
        } else if (parm.length == 2) {
            inUrl = parm[0];
            if ("1".equals(parm[1])) {
                isDelete = true;
            }
        }

        try {
            if (isDelete) {
                String[] res = selectTaskFromDB(inUrl);
                if (res != null && res[1] != null) {
                    File file = new File(res[0]);
                    if (file.exists()) {
                        file.delete();
                    }
                }
            }

            deleteTaskFromDB(inUrl);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void cancelDownload(String[] parm) {
        if (parm.length < 1) {
            return;
        }
        String dlUrl = parm[0];

        String inOpCode = url_objectMap.get(dlUrl);
        if (!BUtility.isNumeric(inOpCode)) {
            return;
        }
        DownLoadAsyncTask dlTask = m_objectMap.remove(Integer
                .parseInt(inOpCode));
        if (dlTask != null) {
            dlTask.cancel(true);
            dlTask = null;
        }
        if (parm.length > 1) {
            if ("1".equals(parm[1])) {
                String[] res = selectTaskFromDB(dlUrl);
                if (res != null && res[1] != null) {
                    File file = new File(res[0]);
                    if (file.exists()) {
                        file.delete();
                    }
                }
                deleteTaskFromDB(dlUrl);
            }
        }

        url_objectMap.clear();
    }

    private class DownLoadAsyncTask extends AsyncTask<String, Integer, Boolean> {
        HttpGet request = null;
        HttpClient httpClient = null;
        BufferedInputStream bis = null;
        HttpResponse response = null;
        RandomAccessFile outputStream = null;
        DownloadPercentage m_dlPercentage = new DownloadPercentage();
        long downLoaderSise = 0;
        long fileSize = 0;
        public int state = F_STATE_CREATE_DOWNLOADER;
        private String op;
        private boolean isError = false;

        @Override
        protected void onCancelled() {
            try {
                if (!op.isEmpty() && !isError) {
                    cbToJs(Integer.parseInt(op), fileSize, "0", EUExCallback.F_C_CB_CancelDownLoad);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        protected Boolean doInBackground(String... params) {
            try {
                Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                op = params[3];
                request = new HttpGet(params[0]);
                if (params[0].startsWith(BUtility.F_HTTP_PATH)) {
                    httpClient = Http.getHttpClient(60 * 1000);
                } else {
                    // https
                    if (mHasCert) {
                        // 存在预置证书
                        String certPath = "file:///android_asset/widget/wgtRes/clientCertificate.p12";
                        WWidgetData wd = mBrwView.getRootWidget();
                        String appId = wd.m_appId;
                        String cPassWord = EUExUtil.getCertificatePsw(mContext,
                                appId);
                        httpClient = Http.getHttpsClientWithCert(cPassWord,
                                certPath, 60 * 1000, mContext);
                    } else {
                        httpClient = Http.getHttpsClient(60 * 1000);
                    }
                }
                httpClient.getParams().setParameter(ClientPNames.HANDLE_REDIRECTS,true);//自动处理重定向
                String cookie = getCookie(params[0]);
                if (cookie != null && cookie.length() != 0) {
                    request.setHeader("Cookie", cookie);
                }

                addHeaders();
                if (null != mCurWData) {
                    request.setHeader(
                            KEY_APPVERIFY,
                            getAppVerifyValue(mCurWData,
                                    System.currentTimeMillis()));
                    request.setHeader(XMAS_APPID, mCurWData.m_appId);
                }

                File file = new File(params[1]);
                if (!file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }
                if ("1".equals(params[2])) {
                    outputStream = new RandomAccessFile(params[1], "rw");
                    downLoaderSise = Integer.parseInt(String
                            .valueOf(outputStream.length()));// 读取文件的大小，从而判断文件是否存在或者是否已经下载完成
                    String[] res = selectTaskFromDB(params[0]);
                    if (res != null) {
                        long fileSize = Long.valueOf(res[1]);
                        if (fileSize != 0 && fileSize == downLoaderSise) {
                            // 若文件存在并且文件大小等于数据库中实际的文件大小，则认为文件已经下载完成
                            // 统一在onPost里回调
//                            cbToJs(Integer.parseInt(params[3]), fileSize, "100", EUExCallback.F_C_FinishDownLoad);
                            return true;
                        }
                    }
                    // res为空说明已经点清除了下载信息，需要重新下
                    else {
                        downLoaderSise = 0;
                        file = new File(params[1]);
                        if (file.exists()) {
                            if (outputStream != null) {
                                outputStream.close();
                                outputStream = null;
                            }
                            file.delete();
                        }
                    }
                    request.setHeader("RANGE", "bytes=" + downLoaderSise + "-");

                } else {
                    file = new File(params[1]);
                    if (file.exists()) {
                        if (outputStream != null) {
                            outputStream.close();
                            outputStream = null;
                        }
                        file.delete();
                    }
                }
                response = httpClient.execute(request);
                int responseCode = response.getStatusLine().getStatusCode();
                if (responseCode == HttpStatus.SC_OK || responseCode == 206) {
                    fileSize = response.getEntity().getContentLength();
                    if (outputStream == null) {
                        outputStream = new RandomAccessFile(params[1], "rw");
                    }

                    if ("1".equals(params[2])) {
                        outputStream.seek(downLoaderSise);
                        fileSize += downLoaderSise;
                        addTaskToDB(params[0], params[1], fileSize);
                    }
                    m_dlPercentage.init(fileSize, Integer.parseInt(params[3]));

                    bis = new BufferedInputStream(response.getEntity()
                            .getContent());
                    byte buf[] = new byte[64 * 1024];
                    while (!isCancelled()) {
                        // 循环读取
                        int numread = bis.read(buf);
                        if (numread == -1) {
                            break;
                        }
                        downLoaderSise += numread;
                        outputStream.write(buf, 0, numread);
                        if (fileSize != -1) {
                            m_dlPercentage.sendMessage(downLoaderSise);
                        }
                        // 让线程休眠100ms
                        // 为了使下载速度加快，取消休眠代码，以目前手机平台的处理速度，此代码用处暂时可以认为只有坏处没有好处。
                    }
                    if (fileSize <= downLoaderSise) {
                        //统一在onPost里回调
//                        cbToJs(Integer.parseInt(params[3]), fileSize, "100", EUExCallback.F_C_FinishDownLoad);
                    }
                } else {
                    isError = true;
                    cbToJs(Integer.parseInt(params[3]), fileSize, "0", EUExCallback.F_C_DownLoadError);
                }
            } catch (Exception e) {
                isError = true;
                cbToJs(Integer.parseInt(params[3]), fileSize, "0", EUExCallback.F_C_DownLoadError);
                e.printStackTrace();
            } finally {
                if (request != null) {
                    request = null;
                }
                if (httpClient != null) {
                    httpClient = null;
                }
                if (response != null) {
                    response = null;
                }
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    outputStream = null;
                }
                if ("1".equals(params[2])) {
                    updateTaskFromDB(params[0], downLoaderSise);
                }
            }
            return null;
        }

        private void addHeaders() {
            if (null != request && null != headersMap) {
                Set<Entry<String, String>> entrys = headersMap.entrySet();
                for (Map.Entry<String, String> entry : entrys) {

                    request.setHeader(entry.getKey(), entry.getValue());
                }
            }
        }

        /**
         * <p>Runs on the UI thread after {@link #doInBackground}. The
         * specified result is the value returned by {@link #doInBackground}.</p>
         * <p>
         * <p>This method won't be invoked if the task was cancelled.</p>
         *
         * @param b The result of the operation computed by {@link #doInBackground}.
         * @see #onPreExecute
         * @see #doInBackground
         * @see #onCancelled(Object)
         */
        @Override
        protected void onPostExecute(Boolean b) {
            if (!isError && b) {
                cbToJs(Integer.parseInt(op), fileSize, "100", EUExCallback.F_C_FinishDownLoad);
            }
        }
    }

    private class DownloadPercentage {
        long fileSize;
        int opCode;
        DecimalFormat df = new DecimalFormat();

        public void init(long fileSize2, int inOpCode) {
            fileSize = fileSize2;
            opCode = inOpCode;
            df.setMaximumFractionDigits(2);
            df.setMinimumFractionDigits(0);
        }

        public void sendMessage(long downSize) {
            cbToJs(opCode, fileSize, df.format(downSize * 100 / fileSize), EUExCallback.F_C_DownLoading);
        }
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        String m_dbName;
        Context m_context;

        DatabaseHelper(Context context, String dbName, int dbVer) {
            super(context, dbName, null, dbVer);
            m_dbName = dbName;
            m_context = context;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            m_context.deleteDatabase(m_dbName);

        }
    }

    private String getNowTime() {
        Time time = new Time();
        time.setToNow();
        int year = time.year;
        int month = time.month + 1;
        int day = time.monthDay;
        int minute = time.minute;
        int hour = time.hour;
        int sec = time.second;
        return year + "-" + month + "-" + day + " " + hour + ":" + minute + ":"
                + sec;
    }

    @Override
    protected boolean clean() {
        try {
            Iterator<Integer> iterator = m_objectMap.keySet().iterator();
            while (iterator.hasNext()) {
                DownLoadAsyncTask object = m_objectMap.get(iterator.next());
                if (object != null) {
                    object.cancel(true);
                    object = null;
                }
            }
            m_objectMap.clear();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (m_database != null) {
            m_database.close();
            m_databaseHelper.close();
            m_database = null;
            m_databaseHelper = null;
        }
        return false;
    }

    public void setHeaders(String[] params) {
        if (params.length < 2 || null == params) {
            return;
        }
        String opCode = params[0];
        String headJson = params[1];
        if (m_objectMap.get(Integer.parseInt(opCode)) != null) {
            try {
                JSONObject json = new JSONObject(headJson);
                Iterator<?> keys = json.keys();
                while (keys.hasNext()) {
                    String key = (String) keys.next();
                    String value = json.getString(key);
                    headersMap.put(key, value);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    private void checkAppStatus(Context inActivity, String appId) {
        try {
            String appstatus = ResoureFinder.getInstance().getString(
                    inActivity, "appstatus");
            byte[] appstatusToByte = PEncryption.HexStringToBinary(appstatus);
            String appstatusDecrypt = new String(PEncryption.os_decrypt(
                    appstatusToByte, appstatusToByte.length, appId));
            String[] appstatuss = appstatusDecrypt.split(",");
            if (appstatuss == null || appstatuss.length == 0) {
                return;
            }
            if ("1".equals(appstatuss[8])) {
                Log.i(tag, "isCertificate: true");
                mHasCert = true;
            }
        } catch (Exception e) {
            Log.w(tag, e.getMessage(), e);
        }

    }

    /**
     * 添加验证头
     *
     * @param curWData  当前widgetData
     * @param timeStamp 当前时间戳
     * @return
     */
    private String getAppVerifyValue(WWidgetData curWData, long timeStamp) {
        String value = null;
        String md5 = getMD5Code(curWData.m_appId + ":" + curWData.m_appkey
                + ":" + timeStamp);
        value = "md5=" + md5 + ";ts=" + timeStamp;
        return value;

    }

    private String getMD5Code(String value) {
        if (value == null) {
            value = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.reset();
            md.update(value.getBytes());
            byte[] md5Bytes = md.digest();
            StringBuffer hexValue = new StringBuffer();
            for (int i = 0; i < md5Bytes.length; i++) {
                int val = ((int) md5Bytes[i]) & 0xff;
                if (val < 16)
                    hexValue.append("0");
                hexValue.append(Integer.toHexString(val));
            }
            return hexValue.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * plugin里面的子应用的appId和appkey都按照主应用为准
     */
    private WWidgetData getWidgetData(EBrowserView view) {
        WWidgetData widgetData = view.getCurrentWidget();
        String indexUrl = widgetData.m_indexUrl;
        Log.i("uexDownloaderMgr", "m_indexUrl:" + indexUrl);
        if (widgetData.m_wgtType != 0) {
            if (indexUrl.contains("widget/plugin")) {
                return view.getRootWidget();
            }
        }
        return widgetData;
    }
}