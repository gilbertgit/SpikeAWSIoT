package com.voidworx.spikeawsiot

import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

import kotlinx.android.synthetic.main.activity_main.*
import com.amazonaws.regions.Regions
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobile.client.AWSMobileClient
import com.amazonaws.mobile.client.Callback
import com.amazonaws.mobile.client.UserStateDetails
import com.amazonaws.mobileconnectors.iot.AWSIotKeystoreHelper
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttLastWillAndTestament
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttNewMessageCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import com.amazonaws.regions.Region
import com.amazonaws.services.iot.AWSIotClient
import com.amazonaws.services.iot.model.AttachPrincipalPolicyRequest
import com.amazonaws.services.iot.model.CreateKeysAndCertificateRequest
import com.amazonaws.services.iot.model.CreateKeysAndCertificateResult

import java.io.UnsupportedEncodingException
import java.security.KeyStore
import java.util.UUID
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity() {

    val LOG_TAG = MainActivity::class.java.canonicalName

    // --- Constants to modify per your configuration ---

    // IoT endpoint
    // AWS Iot CLI describe-endpoint call returns: XXXXXXXXXX.iot.<region>.amazonaws.com
    private val CUSTOMER_SPECIFIC_ENDPOINT = "ah2fpu2q2t4m4-ats.iot.us-east-1.amazonaws.com"
    // Name of the AWS IoT policy to attach to a newly created certificate
    private val AWS_IOT_POLICY_NAME = "android-connect"

    // Region of AWS IoT
    private val MY_REGION = Regions.US_EAST_1
    // Filename of KeyStore file on the filesystem
    private val KEYSTORE_NAME = "iot_keystore"
    // Password for the private key in the KeyStore
    private val KEYSTORE_PASSWORD = "password"
    // Certificate and key aliases in the KeyStore
    private val CERTIFICATE_ID = "default"


    lateinit var tvLastMessage: TextView
    lateinit var tvStatus: TextView
    lateinit var tvClientId: TextView

    lateinit var btnConnect: Button

    lateinit var txtSubscribe: EditText
    lateinit var txtTopic: EditText
    lateinit var txtMessage: EditText



    var mIotAndroidClient: AWSIotClient? = null
    var mqttManager: AWSIotMqttManager? = null
    var clientId: String = "GilbertsAndroidDevice"
    var keystorePath: String = ""
    var keystoreName: String = ""
    var keystorePassword: String = ""

    var clientKeyStore: KeyStore? = null
    var certificateId: String = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        tvLastMessage = findViewById(R.id.tvLastMessage)
        tvStatus = findViewById(R.id.tvStatus)
        tvClientId = findViewById(R.id.tvClientId)

        btnConnect = findViewById(R.id.btnConnect)

        txtSubscribe = findViewById(R.id.txtSubcribe)
        txtTopic = findViewById(R.id.txtTopic)
        txtMessage = findViewById(R.id.txtMessage)

        // Initialize the AWS Cognito credentials provider
        AWSMobileClient.getInstance().initialize(this, object : Callback<UserStateDetails> {
            override fun onResult(result: UserStateDetails) {
                initIoTClient()
            }

            override fun onError(e: Exception) {
                Log.e(LOG_TAG, "onError: ", e)
            }
        })

        fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
        }
    }

    fun initIoTClient() {
        val region = Region.getRegion(MY_REGION)

        // MQTT Client
        mqttManager = AWSIotMqttManager(clientId, CUSTOMER_SPECIFIC_ENDPOINT)

        // Set keepalive to 10 seconds.  Will recognize disconnects more quickly but will also send
        // MQTT pings every 10 seconds.
        mqttManager?.keepAlive = 10

        // Set Last Will and Testament for MQTT.  On an unclean disconnect (loss of connection)
        // AWS IoT will publish this message to alert other clients.
        var lwt = AWSIotMqttLastWillAndTestament("my/lwt/topic",
                "Android client lost connection", AWSIotMqttQos.QOS0)
        mqttManager?.mqttLastWillAndTestament = lwt

        // IoT Client (for creation of certificate if needed)
        mIotAndroidClient = AWSIotClient(AWSMobileClient.getInstance())
        mIotAndroidClient?.setRegion(region)

        keystorePath = filesDir.path
        keystoreName = KEYSTORE_NAME
        keystorePassword = KEYSTORE_PASSWORD
        certificateId = CERTIFICATE_ID

        // To load cert/key from keystore on filesystem
        try {
            if (AWSIotKeystoreHelper.isKeystorePresent(keystorePath, keystoreName)) {
                if (AWSIotKeystoreHelper.keystoreContainsAlias(certificateId, keystorePath,
                        keystoreName, keystorePassword)) {
                    Log.i(LOG_TAG, "Certificate " + certificateId
                            + " found in keystore - using for MQTT.")
                    // load keystore from file into memory to pass on connection
                    clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(certificateId,
                            keystorePath, keystoreName, keystorePassword)
                    /* initIoTClient is invoked from the callback passed during AWSMobileClient initialization.
                    The callback is executed on a background thread so UI update must be moved to run on UI Thread. */
                    runOnUiThread { btnConnect.isEnabled = true }

                } else {
                    Log.i(LOG_TAG, "Key/cert $certificateId not found in keystore.")
                }
            } else {
                Log.i(LOG_TAG, "Keystore $keystorePath/$keystoreName not found.")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "An error occurred retrieving cert/key from keystore.", e)
        }

        if (clientKeyStore == null) {
            Log.i(LOG_TAG, "Cert/key was not found in keystore - creating new key and certificate.")

            thread { kotlin.run {
                try {
                    // Create a new private key and certificate. This call
                    // creates both on the server and returns them to the
                    // device.
                    var createKeysAndCertificateRequest = CreateKeysAndCertificateRequest()
                    createKeysAndCertificateRequest.setAsActive = true
                    val createKeysAndCertificateResult: CreateKeysAndCertificateResult = mIotAndroidClient!!.createKeysAndCertificate(createKeysAndCertificateRequest)
                    Log.i(LOG_TAG,
                            "Cert ID: " +
                                    createKeysAndCertificateResult.certificateId +
                                    " created.")
                    // store in keystore for use in MQTT client
                    // saved as alias "default" so a new certificate isn't
                    // generated each run of this application
                    AWSIotKeystoreHelper.saveCertificateAndPrivateKey(certificateId,
                            createKeysAndCertificateResult.certificatePem,
                            createKeysAndCertificateResult.keyPair.privateKey,
                            keystorePath, keystoreName, keystorePassword)

                    // load keystore from file into memory to pass on
                    // connection
                    clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(certificateId,
                            keystorePath, keystoreName, keystorePassword)

                    // Attach a policy to the newly created certificate.
                    // This flow assumes the policy was already created in
                    // AWS IoT and we are now just attaching it to the
                    // certificate.
                    var policyAttachRequest = AttachPrincipalPolicyRequest()
                    policyAttachRequest.policyName = AWS_IOT_POLICY_NAME
                    policyAttachRequest.principal = createKeysAndCertificateResult.certificateArn
                    mIotAndroidClient!!.attachPrincipalPolicy(policyAttachRequest)

                    runOnUiThread { btnConnect.isEnabled = true }

                    } catch (e:Exception) {
                        Log.e(LOG_TAG,
                                "Exception occurred when generating new private key and certificate.",
                                e)
                    }
            } }.start()
        }
    }

    fun connectClick(view: View) {
        Log.d(LOG_TAG, "clientId = $clientId")

        try {
            mqttManager!!.connect(clientKeyStore, AWSIotMqttClientStatusCallback { status, throwable ->
                Log.d(LOG_TAG, "Status = " + status.toString())
                runOnUiThread {
                            tvStatus.text = status.toString()
                    if (throwable != null) {
                                Log.e(LOG_TAG, "Connection error.", throwable)
                            }
                        }
            })

        } catch (e:Exception) {
            Log.e(LOG_TAG, "Connection error.", e)
            tvStatus.text = "Error! " + e.message
        }
    }

    fun subscribeClick( view: View) {
        val topic: String = txtSubscribe.text.toString()

        Log.d(LOG_TAG, "topic = $topic")

        try {
            mqttManager!!.subscribeToTopic(topic, AWSIotMqttQos.QOS0,
                    AWSIotMqttNewMessageCallback { topic, data ->
                        run {
                            runOnUiThread {
                                try {
                                    var message: String = String(data)
                                    Log.d(LOG_TAG, "Message arrived:")
                                    Log.d(LOG_TAG, "   Topic: $topic")
                                    Log.d(LOG_TAG, " Message: $message")

                                    tvLastMessage.text = message
                                } catch (e: UnsupportedEncodingException) {
                                    Log.e(LOG_TAG, "Message encoding error.", e)
                                }
                            }
                        }
                    })
        } catch (e:Exception) {
            Log.e(LOG_TAG, "Subscription error.", e)
        }
    }

    fun publishClick(view:View) {
        val topic: String = txtTopic.text.toString()
        val msg: String = txtMessage.text.toString()

        try {
            mqttManager!!.publishString(msg, topic, AWSIotMqttQos.QOS0)
        } catch (e:Exception) {
            Log.e(LOG_TAG, "Publish error.", e)
        }
    }

    fun disconnectClick(view: View) {
        try {
            mqttManager!!.disconnect()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Disconnect error.", e)
        }

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }
}
