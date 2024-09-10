#define _WINSOCK_DEPRECATED_NO_WARNINGS
#define _CRT_SECURE_NO_WARNINGS

// Standard Library Includes
#include <iostream>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <vector>
#include <chrono>
#include <cstring>
#include <string>
#include <algorithm>
#include <cmath>


#include <winsock2.h>
#include <ws2tcpip.h>
#include <conio.h>

#include <AsierInhoSerial.h>
#include <GSPAT_SolverV2.h>
#include <Helper/HelperMethods.h>
#include <Helper/microTimer.h>

#include <chrono>
#include <fstream>
#include <iomanip>

// Linker directive for Winsock library
#pragma comment(lib, "Ws2_32.lib")


// Constants
const int PORT = 12345;
const size_t POSITION_SIZE = 4;
const size_t MIN_POINTS = 1;  // Minimum number of focal points (single point scenario)
const size_t MAX_POINTS = 8;  // Maximum number of focal points (8 vertices of a box)
const size_t NUM_POINTS = 1; // the number of trapping/focal points
const size_t NUM_GEOMETRIES = 10; // the number of geometries to be sent to the boards at the same time (maximum allowed = 11)
const size_t TARGET_UPDATE_RATE = 2000; // ensure this is the multiple of the modulationFrequency
const size_t MODULATION_FREQUENCY = 200;
const size_t numPoints = 1; // the number of trapping/focal points
const float MAXIMUM_AMPLITUDE_IN_PASCAL = 3000.0f;
const float MODULATION_INDEX = 1.0f;
const size_t MIN_NUM_SAMPLES = TARGET_UPDATE_RATE / MODULATION_FREQUENCY; // the number of frames
const size_t NUM_SAMPLES = min(MIN_NUM_SAMPLES, NUM_GEOMETRIES);
const size_t TOTAL_SAMPLE_SIZE = NUM_SAMPLES * NUM_POINTS;
// protect std::cout
std::mutex coutMutex;
// Shared data and synchronization
float sharedPositions[POSITION_SIZE * MAX_POINTS] = { 0.0f };
std::atomic<size_t> currentNumPoints{ MIN_POINTS };
std::mutex positionMutex;
std::condition_variable positionCV;
std::atomic<bool> positionUpdated{ false };
std::atomic<bool> finished{ false };
std::ofstream logfile("latency_log.csv");
double compute_duration;
double receive_duration;
double update_duration;

// Function declarations
void listenForUpdates();
void gspat_solver_thread(AsierInhoSerial::AsierInhoSerialBoard* driver, GSPAT::Solver* solver);
void print(const char* str);
auto get_time() {
    return std::chrono::high_resolution_clock::now();
}

template <typename T>
double get_duration_ms(T start, T end) {
    return std::chrono::duration<double, std::milli>(end - start).count();
}
int main() {

    // Initialize AsierInho and GSPAT solver here 
    AsierInhoSerial::RegisterPrintFuncs(print, print, print);
    AsierInhoSerial::AsierInhoSerialBoard* driver = AsierInhoSerial::createAsierInho();
    logfile << "Timestamp,Event,Duration(ms)\n";
    // Setting the board
    bool phaseOnly = false;
    int numBoards = 1; // how many boards do you use?
    float boardHeight = 0.078f; // assuming the board faces down at this height [m]
    int boardIDs[] = { 3 }; // board ID for Wifi, but COM port number for Serial/Bluetooth
    float matBoardToWorld[] = { // only using the top board in this example
        /*top*/
        -1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0,-1, boardHeight,
        0, 0, 0, 1,
    };

    GSPAT_V2::RegisterPrintFuncs(print, print, print);
    GSPAT::Solver* solver = GSPAT_V2::createSolver(64);
    if (!driver->connect(numBoards, boardIDs, matBoardToWorld)) {
        printf("Failed to connect to board.");
        // return 1;
    }

    // Connect to the boards and get some configurations
    float transducerPositions[128 * 3], transducerNormals[128 * 3], amplitudeAdjust[128];
    int mappings[128], phaseDelays[128], numDiscreteLevels;
    driver->readParameters(transducerPositions, transducerNormals, mappings, phaseDelays, amplitudeAdjust, &numDiscreteLevels);
    solver->setBoardConfig(transducerPositions, transducerNormals, mappings, phaseDelays, amplitudeAdjust, numDiscreteLevels);

    // Prepare the transformation matrix for high-level update
    float mI[] = { 1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1 };
    float matrix[16 * NUM_POINTS];
    for (int i = 0; i < NUM_POINTS; i++)
        memcpy(&matrix[16 * i], mI, 16 * sizeof(float));

    // Set your divider
    unsigned int divider = 40000 / TARGET_UPDATE_RATE;
    driver->sendNewDivider(divider);

    // Prepare for animation
    printf("Press any key to start animation.\n");
    _getch();
    printf("Press 'X' to destroy the solver.\n");

    // Start threads
    std::thread listenerThread(listenForUpdates);
    std::thread solverThread(gspat_solver_thread, driver, solver);

    // Check for 'X' key press to terminate
    while (!finished) {
        if (_kbhit()) {
            switch (_getch()) {
            case 'x':
            case 'X':
                finished = true;
                std::cout << "Set finished to true, waiting for threads to join...\n";
                break;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));  // Avoid busy-waiting
    }

    SOCKET exitSocket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in exitAddr;
    exitAddr.sin_family = AF_INET;
    exitAddr.sin_addr.s_addr = inet_addr("127.0.0.1");
    exitAddr.sin_port = htons(PORT);
    connect(exitSocket, (SOCKADDR*)&exitAddr, sizeof(exitAddr));
    closesocket(exitSocket);

    double total_latency = compute_duration + receive_duration + update_duration;
    logfile << std::fixed << std::setprecision(3)
        << std::chrono::duration<double>(get_time().time_since_epoch()).count() << ","
        << "TotalLatency," << total_latency << "\n";

    // Wait for threads to finish
    listenerThread.join();
    std::cout << "Listener thread joined.\n";
    solverThread.join();
    std::cout << "Solver thread joined.\n";

    // Cleanup
    driver->turnTransducersOff();
    std::cout << "Turning off transducers...\n";
    Sleep(100);
    driver->disconnect();
    std::cout << "Disconnecting driver...\n";
    delete driver;
    std::cout << "Deleting driver...\n";
    delete solver;
    std::cout << "Deleting solver...\n";

    std::cout << "Cleanup complete.\n";
    std::cout.flush();
    Sleep(500);

    return 0;
}



void threadSafeCout(const std::string& message) {
    std::lock_guard<std::mutex> lock(coutMutex);
    std::cout << message << std::endl;
}

void listenForUpdates() {
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        threadSafeCout("WSAStartup failed");
        return;
    }

    SOCKET ListenSocket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (ListenSocket == INVALID_SOCKET) {
        threadSafeCout("Error creating socket: " + std::to_string(WSAGetLastError()));
        WSACleanup();
        return;
    }

    sockaddr_in service;
    service.sin_family = AF_INET;
    service.sin_addr.s_addr = INADDR_ANY;
    service.sin_port = htons(PORT);

    if (bind(ListenSocket, (SOCKADDR*)&service, sizeof(service)) == SOCKET_ERROR) {
        threadSafeCout("bind failed: " + std::to_string(WSAGetLastError()));
        closesocket(ListenSocket);
        WSACleanup();
        return;
    }

    if (listen(ListenSocket, SOMAXCONN) == SOCKET_ERROR) {
        threadSafeCout("Listen failed: " + std::to_string(WSAGetLastError()));
        closesocket(ListenSocket);
        WSACleanup();
        return;
    }

    threadSafeCout("Listening for position updates on port " + std::to_string(PORT) + "...");

    char recvbuf[512];
    int recvbuflen = sizeof(recvbuf) - 1;  // Leave space for null terminator

    while (!finished) {
        auto receive_start = get_time();
        SOCKET ClientSocket = accept(ListenSocket, NULL, NULL);
        if (ClientSocket == INVALID_SOCKET) {
            threadSafeCout("accept failed: " + std::to_string(WSAGetLastError()));
            continue;
        }

        int iResult = recv(ClientSocket, recvbuf, recvbuflen, 0);
        if (iResult > 0) {
            recvbuf[iResult] = '\0'; // Ensure null-termination
            std::string receivedData(recvbuf);
            threadSafeCout("Received raw data: " + receivedData);

            std::vector<float> newPositions;
            std::istringstream iss(receivedData);
            std::string token;
            while (std::getline(iss, token, ',')) {
                try {
                    newPositions.push_back(std::stof(token));
                }
                catch (const std::exception& e) {
                    threadSafeCout("Error parsing float: " + std::string(e.what()));
                }
            }

            if (newPositions.size() >= POSITION_SIZE && newPositions.size() <= POSITION_SIZE * MAX_POINTS) {
                std::lock_guard<std::mutex> lock(positionMutex);
                std::copy(newPositions.begin(), newPositions.end(), sharedPositions);
                size_t actualPoints = newPositions.size() / POSITION_SIZE;

                if (actualPoints == 1 && sharedPositions[3] == 1.0f) {
                    currentNumPoints = 1;
                    positionUpdated = true;
                    positionCV.notify_one();
                    threadSafeCout("Received single focal point update");
                }
                else if (actualPoints == 8) {
                    bool allValid = true;
                    for (size_t i = 0; i < 8; ++i) {
                        if (sharedPositions[i * POSITION_SIZE + 3] != 2.0f) {
                            allValid = false;
                            break;
                        }
                    }
                    if (allValid) {
                        currentNumPoints = 8;
                        positionUpdated = true;
                        positionCV.notify_one();
                        threadSafeCout("Received 8 vertices of the box");
                    }
                    else {
                        threadSafeCout("Received invalid box data");
                    }
                }
                else {
                    threadSafeCout("Received unexpected number of points: " + std::to_string(actualPoints));
                }
            }
            else {
                threadSafeCout("Received invalid position data or pet outside the camera view");
            }
        }
        else if (iResult == 0) {
            threadSafeCout("Connection closing...");
        }
        else {
            threadSafeCout("recv failed: " + std::to_string(WSAGetLastError()));
        }

        closesocket(ClientSocket);
        threadSafeCout("Closed client socket");
        auto receive_end = get_time();
        receive_duration = get_duration_ms(receive_start, receive_end);
        logfile << std::fixed << std::setprecision(3)
            << std::chrono::duration<double>(receive_start.time_since_epoch()).count() << ","
            << "DataReception," << receive_duration << "\n";
    }

    closesocket(ListenSocket);
    WSACleanup();
    threadSafeCout("Listener thread exiting");
}

void gspat_solver_thread(AsierInhoSerial::AsierInhoSerialBoard* driver, GSPAT::Solver* solver) {
    // Define the identity matrix
    float mI[] = { 1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1 };

    float positions[4 * TOTAL_SAMPLE_SIZE * MAX_POINTS] = { 0.0f };
    float amplitudes[TOTAL_SAMPLE_SIZE * MAX_POINTS];
    float matrix[16 * MAX_POINTS];
    for (int i = 0; i < MAX_POINTS; i++) {
        memcpy(&matrix[16 * i], mI, 16 * sizeof(float));
    }
    bool phaseOnly = true;


    int posInd = 0, ampInd = 0;
    DWORD updateRate = TARGET_UPDATE_RATE / NUM_GEOMETRIES;
    DWORD interval = 1000000 / updateRate;
    DWORD currTime = microTimer::uGetTime();
    DWORD prevTime = currTime;
    DWORD startTime = currTime, endTime;
    int numSolutions = TARGET_UPDATE_RATE;
    DWORD lastReported = microTimer::uGetTime();

    while (!finished) {
        

        bool validPositionUpdate = false;

        // Wait for position update
        {
            auto compute_start = get_time();
            std::unique_lock<std::mutex> lock(positionMutex);
            if (positionCV.wait_for(lock, std::chrono::seconds(1),
                [] { return positionUpdated.load() || finished.load(); })) {
                if (finished) break;

                size_t numPoints = currentNumPoints.load();
                for (size_t p = 0; p < numPoints; p++) {
                    float x = sharedPositions[4 * p + 0];
                    float y = sharedPositions[4 * p + 1];
                    float z = sharedPositions[4 * p + 2];
                    float w = sharedPositions[4 * p + 3];
                    
                    if ((numPoints == 1 && w == 1.0f) || (numPoints == 8 && w == 2.0f)) {
                        for (int s = 0; s < NUM_SAMPLES; s++) {
                            positions[4 * s * numPoints + 4 * p + 0] = x;
                            positions[4 * s * numPoints + 4 * p + 1] = y;
                            positions[4 * s * numPoints + 4 * p + 2] = z;
                            positions[4 * s * numPoints + 4 * p + 3] = 1;
                        }
                        validPositionUpdate = true;
                    }
                }

                if (validPositionUpdate) {
                    std::cout << "Updated to " << numPoints << " focal point(s)\n";
                } else {
                    std::cout << "Received invalid position data\n";
                }

                positionUpdated = false;
                auto compute_end = get_time();
                compute_duration = get_duration_ms(compute_start, compute_end);
                logfile << std::fixed << std::setprecision(3)
                    << std::chrono::duration<double>(compute_start.time_since_epoch()).count() << ","
                    << "PhaseComputation," << compute_duration << "\n";

            }
        }

        if (validPositionUpdate) {
            size_t numPoints = currentNumPoints.load();
            
            // Update amplitudes
            for (int s = 0; s < NUM_SAMPLES; s++) {
                for (size_t p = 0; p < numPoints; p++) {
                    float angle = 2 * M_PI * s / NUM_SAMPLES;
                    amplitudes[s * numPoints + p] = MAXIMUM_AMPLITUDE_IN_PASCAL *
                        (1 + MODULATION_INDEX * sin(angle)) / (1. + MODULATION_INDEX);
                }
            }

            // Initial solution
            GSPAT::Solution* initialSolution = solver->createSolution(numPoints, 1, phaseOnly, positions, amplitudes, matrix, matrix);
            std::cout << "First GSPAT solver call\n";
            solver->compute(initialSolution);
            unsigned char* initialMsg;
            initialSolution->finalMessages(&initialMsg);
            driver->updateMessage(initialMsg);
            solver->releaseSolution(initialSolution);
            auto update_start = get_time();
            // Continuous update loop
            while (!positionUpdated && !finished) {
                
                GSPAT::Solution* loopSolution = solver->createSolution(numPoints, NUM_GEOMETRIES, true, &positions[posInd], &amplitudes[ampInd], matrix, matrix);
                solver->compute(loopSolution);
                unsigned char* loopMsg;
                loopSolution->finalMessages(&loopMsg);

                // Wait until certain amount of time to send the data at the target update rate
                while ((currTime = microTimer::uGetTime()) - prevTime < interval) { ; }
                prevTime = currTime;

                driver->updateMessages(loopMsg, NUM_GEOMETRIES);
                solver->releaseSolution(loopSolution);

                // Update indices
                posInd += 4 * NUM_GEOMETRIES * numPoints;
                if (posInd >= 4 * TOTAL_SAMPLE_SIZE * numPoints) posInd = 0;
                ampInd += NUM_GEOMETRIES * numPoints;
                if (ampInd >= TOTAL_SAMPLE_SIZE * numPoints) ampInd = 0;

                // Performance monitoring
                numSolutions -= NUM_GEOMETRIES;
                if (numSolutions <= 0) {
                    numSolutions = TARGET_UPDATE_RATE;
                    DWORD currentTime = microTimer::uGetTime();
                    float timePerComputation = (currentTime - lastReported) / 1000000.f;
                    printf("Time Per Computation = %f; Target Update Rate: %d; Actual Update Rate: %f\n",
                        timePerComputation, TARGET_UPDATE_RATE, TARGET_UPDATE_RATE / timePerComputation);

                    for (size_t p = 0; p < numPoints; p++) {
                        printf("Current Position for point %zu: X=%f, Y=%f, Z=%f\n",
                            p, positions[posInd + 4 * p], positions[posInd + 4 * p + 1], positions[posInd + 4 * p + 2]);
                    }

                    lastReported = currentTime;
                }

                // Check if there's a new position update
                {
                    std::lock_guard<std::mutex> lock(positionMutex);
                    if (positionUpdated) {
                        break;
                    }
                }


            }
            auto update_end = get_time();
            update_duration = get_duration_ms(update_start, update_end);
            logfile << std::fixed << std::setprecision(3)
                << std::chrono::duration<double>(update_start.time_since_epoch()).count() << ","
                << "GSPATComputation," << update_duration << "\n";

        }
        else {
            // If we don't have a valid position update, we wait a bit before checking again
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }

        if (finished) break;


    }

    std::cout << "GSPAT solver thread exiting\n";
}

void print(const char* str) {
    std::cout << str << std::endl;
}