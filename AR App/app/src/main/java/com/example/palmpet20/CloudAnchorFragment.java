
package com.example.palmpet20;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Future;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.helpers.AABB;
import com.google.ar.core.helpers.CameraPermissionHelper;
import com.google.ar.core.helpers.FirebaseManager;
import com.google.ar.core.helpers.PointClusteringHelper;
import com.google.ar.core.helpers.ResolveDialogFragment;
import com.google.ar.core.helpers.SnackbarHelper;
import com.google.ar.core.helpers.TapHelper;
import com.google.ar.core.helpers.TrackingStateHelper;
import com.google.ar.core.rendering.BackgroundRenderer;
import com.google.ar.core.rendering.BoxRenderer;
import com.google.ar.core.rendering.ObjectRenderer;
import com.google.ar.core.rendering.ObjectRenderer.BlendMode;
import com.google.ar.core.rendering.PlaneRenderer;
import com.google.ar.core.rendering.PointCloudRenderer;
import com.google.ar.core.helpers.DisplayRotationHelper;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import android.widget.EditText;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import com.google.ar.core.Config;
import android.os.SystemClock;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public class CloudAnchorFragment extends Fragment implements GLSurfaceView.Renderer {
  private long startTime;
  private FileOutputStream logFile;
  private AABB currentBox = null;
  private long lastBoxUpdateTime = 0;
  private static final long BOX_UPDATE_COOLDOWN = 5000; // 5 seconds in milliseconds

  private static final String TAG = CloudAnchorFragment.class.getSimpleName();

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final PlaneRenderer planeRenderer = new PlaneRenderer();
  private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();
  private final ObjectRenderer virtualObject = new ObjectRenderer();
  private final ObjectRenderer virtualObjectShadow = new ObjectRenderer();
  private final DepthRenderer depthRenderer = new DepthRenderer();
  private final BoxRenderer boxRenderer = new BoxRenderer();
  private boolean isDepthMode = false;

  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Button summonButton;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private TrackingStateHelper trackingStateHelper;
  private TapHelper tapHelper;
  private FirebaseManager firebaseManager;


  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] anchorMatrix = new float[16];
  private static final String SEARCHING_PLANE_MESSAGE = "Searching for surfaces...";
  private final float[] andyColor = {1.0f, 1.0f, 1.0f, 1.0f};

  private EditText etIpAddress;
  private float[] convertedPosition = new float[4];
  private String ipAddress;

  private int frameCounter = 0;
  private static final int UPDATE_INTERVAL = 60; // Update every 60 frames
  private float[] lastSentPosition = new float[4];
  private static final float POSITION_THRESHOLD = 0.002f; // 2mm threshold
  private boolean isFirstUpdate = true;

  private boolean isBoxCurrentlyInView = false;
  private long lastBoxDataSentTime = 0;

  private Button changePetButton;
  private boolean isPetilil = true;

  @Nullable
  private Anchor currentAnchor = null;

  @Nullable
  private Future future = null;

  private void onSummonButtonPressed() {
    ResolveDialogFragment dialog = ResolveDialogFragment.createWithOkListener(
            this::onShortCodeEntered);
    dialog.show(getActivity().getSupportFragmentManager(), "Summon");
  }

  private void showLongSnackbar(String message, int durationSeconds) {
    if (getActivity() != null) {
      messageSnackbarHelper.showMessageForDuration(getActivity(), message, durationSeconds * 1000);
    }
  }

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    tapHelper = new TapHelper(context);
    trackingStateHelper = new TrackingStateHelper(requireActivity());
    firebaseManager = new FirebaseManager(context);
    Log.d(TAG, "FirebaseManager initialized");
  }

  private void startTimer() {
    startTime = SystemClock.elapsedRealtime();
  }

  private void logEvent(String event) {
    long duration = SystemClock.elapsedRealtime() - startTime;
    String logEntry = String.format("%d,%s,%d\n", System.currentTimeMillis(), event, duration);
    try {
      logFile.write(logEntry.getBytes());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public View onCreateView(
          LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    // Inflate from the Layout XML file.
    View rootView = inflater.inflate(R.layout.cloud_anchor_fragment, container, false);

    try {
      String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
      logFile = getContext().openFileOutput("latency_log_" + timestamp + ".csv", Context.MODE_PRIVATE);
      logFile.write("Timestamp,Event,Duration(ms)\n".getBytes());
    } catch (IOException e) {
      e.printStackTrace();
    }


    // Set up AR view
    GLSurfaceView surfaceView = rootView.findViewById(R.id.surfaceView);
    this.surfaceView = surfaceView;
    displayRotationHelper = new DisplayRotationHelper(requireContext());
    surfaceView.setOnTouchListener(tapHelper);

    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);

    // Set up Clear button
    Button clearButton = rootView.findViewById(R.id.clear_button);
    clearButton.setOnClickListener(v -> onClearButtonPressed());

    // Set up depthRecognitionButton
    Button depthRecognitionButton = rootView.findViewById(R.id.depth_recognition_button);
    depthRecognitionButton.setOnClickListener(v -> toggleDepthMode());

    // Set up summonButton
    summonButton = rootView.findViewById(R.id.summon_button);
    summonButton.setOnClickListener(v -> onSummonButtonPressed());

    changePetButton = rootView.findViewById(R.id.change_pet_button);
    changePetButton.setOnClickListener(v -> onChangePetButtonPressed());

    // Set up IP Address input
    etIpAddress = rootView.findViewById(R.id.etIpAddress);
    Button btnSetIpAddress = rootView.findViewById(R.id.btnSetIpAddress);
    btnSetIpAddress.setOnClickListener(v -> {
      ipAddress = etIpAddress.getText().toString();
      Toast.makeText(getContext(), "IP Address set to: " + ipAddress, Toast.LENGTH_SHORT).show();
    });

    return rootView;
  }

  private void onChangePetButtonPressed() {
    isPetilil = !isPetilil;
    String petName = isPetilil ? "Petilil" : "Wooper : ";
    Toast.makeText(getContext(), "Next anchor will be " + petName, Toast.LENGTH_SHORT).show();
  }


  private void toggleDepthMode() {
    if (boxRenderer == null) {
      Log.e(TAG, "BoxRenderer is not initialized");
      return;
    }
    isDepthMode = !isDepthMode;
    try {
      Config config = session.getConfig();
      if (isDepthMode) {
        if (session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
          config.setDepthMode(Config.DepthMode.RAW_DEPTH_ONLY);
          Toast.makeText(getContext(), "Depth mode enabled", Toast.LENGTH_SHORT).show();
        } else {
          Toast.makeText(getContext(), "Raw depth not supported on this device", Toast.LENGTH_LONG).show();
          isDepthMode = false;
          return;
        }
      } else {
        config.setDepthMode(Config.DepthMode.DISABLED);
        Toast.makeText(getContext(), "Depth mode disabled", Toast.LENGTH_SHORT).show();
      }
      session.configure(config);
    } catch (Exception e) {
      Log.e(TAG, "Error toggling depth mode", e);
      Toast.makeText(getContext(), "Error toggling depth mode", Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(requireActivity())) {
          CameraPermissionHelper.requestCameraPermission(requireActivity());
          return;
        }

        // Create the session.
        session = new Session(requireActivity());
        Config config = new Config(session);
        config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);
        session.configure(config);

      } catch (UnavailableArcoreNotInstalledException
               | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(requireActivity(), message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper
              .showError(requireActivity(), "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
    if (!CameraPermissionHelper.hasCameraPermission(requireActivity())) {
      Toast.makeText(requireActivity(), "Camera permission is needed to run this application",
                      Toast.LENGTH_LONG)
              .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(requireActivity())) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(requireActivity());
      }
      requireActivity().finish();
    }
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      backgroundRenderer.createOnGlThread(getContext());
      planeRenderer.createOnGlThread(getContext(), "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(getContext());

      // Initialize with Petilil
      virtualObject.createOnGlThread(getContext(), "models/Petilil.obj", "models/Petilil.png");
      virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);

      virtualObjectShadow
              .createOnGlThread(getContext(), "models/andy_shadow.obj", "models/andy_shadow.png");
      virtualObjectShadow.setBlendMode(BlendMode.Shadow);
      virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);


      // Initialize depth-related renderers
      initRenderers();

    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }


  private void initRenderers() {
    try {
      depthRenderer.createOnGlThread(getContext());
      boxRenderer.createOnGlThread(getContext());
    } catch (IOException e) {
      Log.e(TAG, "Failed to initialize renderers", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }


  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    try {

      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession.
      Frame frame = session.update();
      Camera camera = frame.getCamera();

      // Handle one tap per frame.
      handleTap(frame, camera);

      // Drawing depends on the state of the world
      backgroundRenderer.draw(frame);

      // If not tracking, don't draw 3D objects.
      if (camera.getTrackingState() == TrackingState.PAUSED) {
        return;
      }

      // Get projection matrix.
      float[] projectionMatrix = new float[16];
      camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

      // Get camera matrix and draw.
      float[] viewMatrix = new float[16];
      camera.getViewMatrix(viewMatrix, 0);

      // Compute lighting from average intensity of the image.
      final float[] colorCorrectionRgba = new float[4];
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

      if (isDepthMode) {
        startTimer();
        FloatBuffer points = null;
        try {
          if (camera.getTrackingState() == TrackingState.TRACKING) {
            points = DepthData.create(frame, camera);
          }
        } catch (Exception e) {
          Log.e(TAG, "Error creating depth data", e);
        }

        if (points != null) {
          DepthData.filterUsingPlanes(points, session.getAllTrackables(Plane.class));

          try {
            depthRenderer.update(points);
            depthRenderer.draw(camera);
          } catch (Exception e) {
            Log.e(TAG, "Error in depth rendering", e);
          }

          long currentTime = System.currentTimeMillis();
          if (currentTime - lastBoxUpdateTime >= BOX_UPDATE_COOLDOWN) {
            try {
              PointClusteringHelper clusteringHelper = new PointClusteringHelper(points);
              AABB nearestCluster = clusteringHelper.findNearestCluster(camera);

              if (nearestCluster != null) {
                currentBox = nearestCluster;
                lastBoxUpdateTime = currentTime;
              }
            } catch (Exception e) {
              Log.e(TAG, "Error finding nearest cluster", e);
            }
          }

          if (currentBox != null) {
            try {
              boxRenderer.draw(currentBox, camera);

              // Check box visibility on every frame
              boolean boxInView = isBoxInView(frame, currentBox);
              // Send box vertices based on cooldown, only if the box is in view
              if (boxInView && currentTime - lastBoxDataSentTime >= BOX_UPDATE_COOLDOWN) {
                float[][] boxVertices = getBoxVertices(currentBox);
                sendBoxVertices(boxVertices);
                logEvent("sendDepthBoxVertices");
                lastBoxDataSentTime = currentTime;
              }

              if (!boxInView) {
                // Box just went out of view, send invalid position immediately
                sendInvalidPosition();
              }

            } catch (Exception e) {
              Log.e(TAG, "Error in box rendering or sending position", e);
            }
          }
        }
      } else {
        // Non-depth mode rendering
        try (PointCloud pointCloud = frame.acquirePointCloud()) {
          pointCloudRenderer.update(pointCloud);
          pointCloudRenderer.draw(viewMatrix, projectionMatrix);
        }

        // Check if we detected at least one plane. If so, hide the loading message.
        if (hasTrackingPlane()) {
          messageSnackbarHelper.hide(getActivity());
        } else {
          messageSnackbarHelper.showMessage(getActivity(), SEARCHING_PLANE_MESSAGE);
        }

        // Visualize planes.
        planeRenderer.drawPlanes(
                session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projectionMatrix);

        // Visualize anchors created by touch.
        if (currentAnchor != null && currentAnchor.getTrackingState() == TrackingState.TRACKING) {
          if (isAnchorInView(frame, currentAnchor)) {
            // Convert the anchor's coordinates
            startTimer();
            float[] newPosition = CoordinateConverter.convertToRelativeCoordinates(currentAnchor, camera);

            // Check if it's time to update and if the position has changed significantly
            if (isFirstUpdate || (frameCounter % UPDATE_INTERVAL == 0 && hasPositionChangedSignificantly(newPosition))) {
              sendPositionToCppScript(newPosition);
              System.arraycopy(newPosition, 0, lastSentPosition, 0, 4);
              isFirstUpdate = false;
            }

            currentAnchor.getPose().toMatrix(anchorMatrix, 0);
            // Update and draw the model and its shadow.
            virtualObject.updateModelMatrix(anchorMatrix, 1f);
            virtualObjectShadow.updateModelMatrix(anchorMatrix, 1f);
            virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, andyColor);
            virtualObjectShadow.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, andyColor);
            logEvent("AnchorCreation");
          } else {
            // Anchor is not in the field of view
            Log.d(TAG, "Anchor is not in the field of view");
            sendInvalidPosition();
          }
        } else if (currentAnchor != null) {
          // Log the anchor state if it's not tracking
          Log.d(TAG, "Anchor state: " + currentAnchor.getTrackingState());
          sendInvalidPosition();
        }
      }

      // Increment frameCounter and reset if necessary
      frameCounter++;
      if (frameCounter >= 3600) {  // Reset counter every ~1-2 minutes (assuming 30-60 FPS)
        frameCounter = 0;
      }

    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  private void sendBoxVertices(float[][] vertices) {
    if (ipAddress == null || ipAddress.isEmpty()) {
      return;
    }

    new Thread(() -> {
      try (Socket socket = new Socket(ipAddress, 12345);
           PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

        StringBuilder message = new StringBuilder();
        for (float[] vertex : vertices) {
          message.append(String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f,",
                  vertex[0], vertex[1], vertex[2], vertex[3]));
        }

        // Remove the trailing comma
        message.setLength(message.length() - 1);

        // Log the message before sending
        Log.d(TAG, "Sending box vvvertices: " + message.toString());

        out.println(message.toString());

        //getActivity().runOnUiThread(() ->
       //         Toast.makeText(getContext(), "Box vertices sent successfully", Toast.LENGTH_SHORT).show());

      } catch (IOException e) {
        e.printStackTrace();
        getActivity().runOnUiThread(() ->
                Toast.makeText(getContext(), "Failed to send box vertices: " + e.getMessage(), Toast.LENGTH_SHORT).show());
      }
    }).start();
  }


  private float[][] getBoxVertices(AABB aabb) {
    return new float[][] {
            {aabb.minX, aabb.minY, aabb.minZ, 2.0f},
            {aabb.maxX, aabb.minY, aabb.minZ, 2.0f},
            {aabb.minX, aabb.maxY, aabb.minZ, 2.0f},
            {aabb.maxX, aabb.maxY, aabb.minZ, 2.0f},
            {aabb.minX, aabb.minY, aabb.maxZ, 2.0f},
            {aabb.maxX, aabb.minY, aabb.maxZ, 2.0f},
            {aabb.minX, aabb.maxY, aabb.maxZ, 2.0f},
            {aabb.maxX, aabb.maxY, aabb.maxZ, 2.0f}
    };
  }

  private boolean isBoxInView(Frame frame, AABB box) {
    if (frame == null || box == null) {
      return false;
    }

    // Get the current projection matrix.
    float[] projectionMatrix = new float[16];
    frame.getCamera().getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

    // Get the current view matrix.
    float[] viewMatrix = new float[16];
    frame.getCamera().getViewMatrix(viewMatrix, 0);

    // Combine the projection and view matrices.
    float[] viewProjectionMatrix = new float[16];
    Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);

    // Get the box's center position.
    float[] boxCenter = new float[4];
    boxCenter[0] = (box.minX + box.maxX) / 2;
    boxCenter[1] = (box.minY + box.maxY) / 2;
    boxCenter[2] = (box.minZ + box.maxZ) / 2;
    boxCenter[3] = 1.0f;

    // Transform the box center position to screen space.
    float[] screenPosition = new float[4];
    Matrix.multiplyMV(screenPosition, 0, viewProjectionMatrix, 0, boxCenter, 0);

    // Perform perspective division
    if (screenPosition[3] <= 0) {
      return false;  // Box is behind the camera
    }

    float normalizedX = screenPosition[0] / screenPosition[3];
    float normalizedY = screenPosition[1] / screenPosition[3];

    // Check if the box's center screen position is within the view bounds
    return (normalizedX >= -1 && normalizedX <= 1 && normalizedY >= -1 && normalizedY <= 1);
  }


  private boolean hasPositionChangedSignificantly(float[] newPosition) {
    if (isFirstUpdate) {
      return true;
    }

    for (int i = 0; i < 3; i++) {  // Only check x, y, z
      if (Math.abs(newPosition[i] - lastSentPosition[i]) > POSITION_THRESHOLD) {
        Log.d(TAG, "Position changed significantly. Axis: " + i +
                ", Old: " + lastSentPosition[i] + ", New: " + newPosition[i]);
        return true;
      }
    }

    return false;
  }
  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private void handleTap(Frame frame, Camera camera) {
    if (currentAnchor != null) {
      return; // Do nothing if there was already an anchor.
    }

    MotionEvent tap = tapHelper.poll();
    if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
      if (!isDepthMode) {
        handleTapInNormalMode(frame, camera, tap);
      } else {
        handleTapInDepthMode(frame, camera, tap);
      }
    }
  }

  private void handleTapInNormalMode(Frame frame, Camera camera, MotionEvent tap) {
    for (HitResult hit : frame.hitTest(tap)) {
      Trackable trackable = hit.getTrackable();
      if ((trackable instanceof Plane
              && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
              && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
              || (trackable instanceof Point
              && ((Point) trackable).getOrientationMode()
              == OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
        placeAnchor(hit.createAnchor(), camera);
        break;
      }
    }
  }

  private void handleTapInDepthMode(Frame frame, Camera camera, MotionEvent tap) {
    try {
      List<HitResult> hitResultList = frame.hitTest(tap);
      for (HitResult hit : hitResultList) {
        Trackable trackable = hit.getTrackable();
        if (trackable instanceof Point) {
          Point point = (Point) trackable;
          if (point.getOrientationMode() == OrientationMode.ESTIMATED_SURFACE_NORMAL) {
            placeAnchor(hit.createAnchor(), camera);
            break;
          }
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "Error handling tap in depth mode", e);
    }
  }

  private void placeAnchor(Anchor newAnchor, Camera camera) {
    currentAnchor = newAnchor;
    getActivity().runOnUiThread(() -> summonButton.setEnabled(false));
    future = session.hostCloudAnchorAsync(currentAnchor, 300, this::onHostComplete);

    try {
      // Create the virtual object based on the current isPetilil value
      if (isPetilil) {
        virtualObject.createOnGlThread(getContext(), "models/Petilil.obj", "models/Petilil.png");
      } else {
        virtualObject.createOnGlThread(getContext(), "models/Wooper.obj", "models/Wooper.png");
      }
      virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read object obj file", e);
    }

    // Get AR space coordinates
    float[] arPosition = currentAnchor.getPose().getTranslation();

    // Get camera relative coordinates
    float[] cameraRelativePosition = CoordinateConverter.convertToRelativeCoordinates(currentAnchor, camera);

    // Create toast message
    String message = String.format(Locale.US, "Now hosting anchor...");
    showShortToast(message);
  }

  /**
   * Checks if we detected at least one plane.
   */
  private boolean hasTrackingPlane() {
    for (Plane plane : session.getAllTrackables(Plane.class)) {
      if (plane.getTrackingState() == TrackingState.TRACKING) {
        return true;
      }
    }
    return false;
  }

  private void onClearButtonPressed() {
    // Clear the anchor from the scene.
    if (currentAnchor != null) {
      currentAnchor.detach();
      currentAnchor = null;
    }

    // The next part is the new addition.
    // Cancel any ongoing asynchronous operations.
    if (future != null) {
      future.cancel();
      future = null;
    }

    summonButton.setEnabled(true);
  }


  private void onHostComplete(String cloudAnchorId, Anchor.CloudAnchorState cloudState) {
    if (cloudState == Anchor.CloudAnchorState.SUCCESS) {
      Log.d(TAG, "sbbbCloud Anchor hosted successfully. ID: " + cloudAnchorId);

      firebaseManager.nextShortCode(new FirebaseManager.ShortCodeListener() {
        @Override
        public void onShortCodeAvailable(Integer shortCode) {
          Log.d(TAG, "sbbbReceived short code: " + shortCode);
          firebaseManager.storeUsingShortCode(shortCode, cloudAnchorId);
          final String message = String.format(Locale.US, "Cloud Anchor Hosted. Short code: %d", shortCode);
          getActivity().runOnUiThread(() -> showToast(message));
        }

        @Override
        public void onError(String errorMessage) {
          Log.e(TAG, "sbbbFirebase error: " + errorMessage);
          getActivity().runOnUiThread(() -> showToast("Firebase error: " + errorMessage));
        }
      });
    } else {
      Log.e(TAG, "sbbbError while hosting: " + cloudState.toString());
      getActivity().runOnUiThread(() -> showToast("Error while hosting: " + cloudState.toString()));
    }
  }

  private void onShortCodeEntered(int shortCode) {
    firebaseManager.getCloudAnchorId(shortCode, new FirebaseManager.CloudAnchorIdListener() {
      @Override
      public void onCloudAnchorIdAvailable(String cloudAnchorId) {
        if (cloudAnchorId == null || cloudAnchorId.isEmpty()) {
          getActivity().runOnUiThread(() ->
                  showToast("A Cloud Anchor ID for the short code " + shortCode + " was not found."));
          return;
        }
        summonButton.setEnabled(false);
        future = session.resolveCloudAnchorAsync(
                cloudAnchorId, (anchor, cloudState) -> onSummonComplete(anchor, cloudState, shortCode));
      }
    });
  }

  private void onSummonComplete(Anchor anchor, Anchor.CloudAnchorState cloudState, int shortCode) {
    if (cloudState == Anchor.CloudAnchorState.SUCCESS) {
      messageSnackbarHelper.showMessage(getActivity(), "Pet Summoned. pet code: " + shortCode);
      currentAnchor = anchor;
    } else {
      messageSnackbarHelper.showMessage(
              getActivity(),
              "Error while resolving anchor with short code "
                      + shortCode
                      + ". Error: "
                      + cloudState.toString());
      summonButton.setEnabled(true);
    }
  }

  private void sendAnchorPosition(Anchor anchor, Camera camera) {
    float[] position = CoordinateConverter.convertToRelativeCoordinates(anchor, camera);
    sendPositionToCppScript(position);
  }

  private void sendPositionToCppScript(float[] position) {
    startTimer();
    if (ipAddress == null || ipAddress.isEmpty()) {
      return;
    }

    new Thread(() -> {
      try (Socket socket = new Socket(ipAddress, 12345);
           PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

        String message;
        if (position[3] == 0) {  // Check if it's an invalid position
          message = "0,0,0,0";
        } else {
          message = String.format("%.5f,%.5f,%.5f,%.5f",
                  position[0], position[1], position[2], position[3]);
        }
        out.println(message);
        logEvent("PositionUpdate");
        getActivity().runOnUiThread(() ->
                Toast.makeText(getContext(), "Position sent successfully", Toast.LENGTH_SHORT).show());

      } catch (IOException e) {
        e.printStackTrace();
        getActivity().runOnUiThread(() ->
                Toast.makeText(getContext(), "Failed to send position: " + e.getMessage(), Toast.LENGTH_SHORT).show());
      }
    }).start();
  }

  private void showShortToast(String message) {
    if (getActivity() != null) {
      getActivity().runOnUiThread(() ->
              Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show());
    }
  }

  private void showToast(String message) {
    if (getActivity() != null) {
      getActivity().runOnUiThread(() ->
              Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show());
    }
  }

  private boolean isAnchorInView(Frame frame, Anchor anchor) {
    if (frame == null || anchor == null) {
      return false;
    }

    // Get the current projection matrix.
    float[] projectionMatrix = new float[16];
    frame.getCamera().getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

    // Get the current view matrix.
    float[] viewMatrix = new float[16];
    frame.getCamera().getViewMatrix(viewMatrix, 0);

    // Combine the projection and view matrices.
    float[] viewProjectionMatrix = new float[16];
    Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);

    // Get the anchor's world position.
    float[] anchorPosition = new float[4];
    anchor.getPose().getTranslation(anchorPosition, 0);
    anchorPosition[3] = 1.0f;

    // Transform the anchor position to screen space.
    float[] screenPosition = new float[4];
    Matrix.multiplyMV(screenPosition, 0, viewProjectionMatrix, 0, anchorPosition, 0);

    // Perform perspective division
    if (screenPosition[3] <= 0) {
      return false;  // Anchor is behind the camera
    }

    float normalizedX = screenPosition[0] / screenPosition[3];
    float normalizedY = screenPosition[1] / screenPosition[3];

    // Check if the anchor's screen position is within the view bounds
    return (normalizedX >= -1 && normalizedX <= 1 && normalizedY >= -1 && normalizedY <= 1);
  }

  private void sendInvalidPosition() {
    float[] invalidPosition = new float[]{0, 0, 0, 0};
    sendPositionToCppScript(invalidPosition);
    Log.d(TAG, "Sending flag: anchor not in view");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    try {
      if (logFile != null) {
        logFile.close();
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

}


