
package com.google.ar.core.helpers;

import com.google.ar.core.Camera;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public class PointClusteringHelper {
    // The resolution of the grid to allocate.  A smaller size means higher fidelity, but also
    // incurs a larger memory footprint.
    private static final float GRID_CELL_SIZE = 0.025f; // Units: meters.

    // Clusters with fewer than this many elements are ignored.
    private static final int MIN_CLUSTER_ELEMENTS = 50;

    public AABB findNearestCluster(Camera camera) {
        List<AABB> clusters = findClusters();
        if (clusters == null || clusters.isEmpty()) {
            return null;
        }

        float[] cameraPos = camera.getPose().getTranslation();
        AABB nearestCluster = null;
        float nearestDistance = Float.MAX_VALUE;

        for (AABB cluster : clusters) {
            float[] clusterCenter = cluster.getCenter();
            float distance = calculateDistance(cameraPos, clusterCenter);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestCluster = cluster;
            }
        }

        return nearestCluster;
    }

    private float calculateDistance(float[] point1, float[] point2) {
        float dx = point1[0] - point2[0];
        float dy = point1[1] - point2[1];
        float dz = point1[2] - point2[2];
        return (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
    }



    // The occupancy grid represents voxels in 3D space.  Each voxel is marked 'true' iff a depth
    // point with high confidence intersects it.  This grid volume represents a cuboid in space
    // defined by the bounding box of the pointcloud.
    private boolean[][][] occupancyGrid;

    // The translational offset of the grid relative to the world coordinates.  This offset is
    // necessary for cases when the bounding box of depth points don't overlap the origin.  This
    // value is equivalent to the minimum corner of the point cloud bounding box.
    private final float[] gridOriginOffset = new float[3];

    public PointClusteringHelper(FloatBuffer points) {
        allocateGrid(points);
    }

    /**
     * Finds clusters of voxels.  Computes a list of axis-aligned bounding boxes representing
     * large areas of depth points.
     */
    public List<AABB> findClusters() {
        // Clusters are found by iterating over each cell's neighbors. As cells are found, they are
        // marked false. This process continues until all cells are false.
        // Because the original grid is modified, this call will only produce results once.
        List<AABB> clusters = new ArrayList<>();
        List<int[]> currentCluster = new ArrayList<>();

        // Searches the grid for clusters.
        int[] index = new int[3];
        for (index[0] = 0; index[0] < occupancyGrid.length; ++index[0]) {
            for (index[1] = 0; index[1] < occupancyGrid[0].length; ++index[1]) {
                for (index[2] = 0; index[2] < occupancyGrid[0][0].length; ++index[2]) {
                    // Attempts to find a cluster containing the current index.
                    depthFirstSearch(index, occupancyGrid, currentCluster);

                    // Only add the cluster if it meets the minimum size requirement.
                    if (currentCluster.size() >= MIN_CLUSTER_ELEMENTS) {
                        // Stores the cluster.
                        clusters.add(computeAABB(currentCluster));
                    }

                    // Clear the current cluster for the next search.
                    currentCluster.clear();
                }
            }
        }

        return clusters;
    }


    /** Finds the bounding box of all points, allocating a 3D grid of sufficient size. */
    private void allocateGrid(FloatBuffer points) {
        // Finds the min/max bounds of the pointcloud.
        AABB bounds = new AABB();
        points.rewind();
        while (points.hasRemaining()) {
            float x = points.get();
            float y = points.get();
            float z = points.get();
            float confidence = points.get();
            if (confidence <= 0) {
                continue;
            }
            bounds.update(x, y, z);
        }

        // Each grid cell is a cube of size GRID_CELL_SIZE^3 cubic meters.
        gridOriginOffset[0] = bounds.minX;
        gridOriginOffset[1] = bounds.minY;
        gridOriginOffset[2] = bounds.minZ;
        int numCellsX = Math.max(1, (int) Math.ceil((bounds.maxX - bounds.minX) / GRID_CELL_SIZE));
        int numCellsY = Math.max(1, (int) Math.ceil((bounds.maxY - bounds.minY) / GRID_CELL_SIZE));
        int numCellsZ = Math.max(1, (int) Math.ceil((bounds.maxZ - bounds.minZ) / GRID_CELL_SIZE));
        occupancyGrid = new boolean[numCellsX][numCellsY][numCellsZ];

        // Populates the grid with occupancy of points.
        points.rewind();
        while (points.hasRemaining()) {
            float x = points.get();
            float y = points.get();
            float z = points.get();
            float confidence = points.get();
            if (confidence <= 0) {
                continue;
            }

            // Finds the voxel that contains this depth point and sets it to true.
            int indexX = (int) Math.floor((x - gridOriginOffset[0]) / GRID_CELL_SIZE);
            int indexY = (int) Math.floor((y - gridOriginOffset[1]) / GRID_CELL_SIZE);
            int indexZ = (int) Math.floor((z - gridOriginOffset[2]) / GRID_CELL_SIZE);
            occupancyGrid[indexX][indexY][indexZ] = true;
        }
    }

    /** Computes the metric bounds of a subset of the grid. */
    private AABB computeAABB(List<int[]> cluster) {
        // Computes the bounds in units of "indices".
        AABB bounds = new AABB();
        for (int[] index : cluster) {
            // The minimum and maximum corners of this grid cell, in units of indices.
            bounds.update(index[0], index[1], index[2]);
            bounds.update(index[0]+1, index[1]+1, index[2]+1);
        }

        // Rescales units from "indices" to "meters".
        bounds.minX = GRID_CELL_SIZE * bounds.minX + gridOriginOffset[0];
        bounds.minY = GRID_CELL_SIZE * bounds.minY + gridOriginOffset[1];
        bounds.minZ = GRID_CELL_SIZE * bounds.minZ + gridOriginOffset[2];
        bounds.maxX = GRID_CELL_SIZE * bounds.maxX + gridOriginOffset[0];
        bounds.maxY = GRID_CELL_SIZE * bounds.maxY + gridOriginOffset[1];
        bounds.maxZ = GRID_CELL_SIZE * bounds.maxZ + gridOriginOffset[2];

        return bounds;
    }

    private static void depthFirstSearch(int[] index, boolean[][][] grid, List<int[]> cluster) {
        if (!inBounds(index, grid) || !grid[index[0]][index[1]][index[2]]) {
            return;  // Not occupied, stop searching in this area.
        }

        // Since the current index is occupied, it can be added to the local cluster. Once added,
        // we reset the grid to avoid cyclic behavior.
        grid[index[0]][index[1]][index[2]] = false;
        cluster.add(index.clone());

        // Search the neighbors.
        depthFirstSearch(new int[]{index[0]-1, index[1], index[2]}, grid, cluster);
        depthFirstSearch(new int[]{index[0]+1, index[1], index[2]}, grid, cluster);
        depthFirstSearch(new int[]{index[0], index[1]-1, index[2]}, grid, cluster);
        depthFirstSearch(new int[]{index[0], index[1]+1, index[2]}, grid, cluster);
        depthFirstSearch(new int[]{index[0], index[1], index[2]-1}, grid, cluster);
        depthFirstSearch(new int[]{index[0], index[1], index[2]+1}, grid, cluster);
    }

    private static boolean inBounds(int[] index, boolean[][][] grid) {
        return index[0] >= 0 && index[0] < grid.length &&
                index[1] >= 0 && index[1] < grid[0].length &&
                index[2] >= 0 && index[2] < grid[0][0].length;
    }
}
