/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam.smos.visat.export;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.swing.progress.ProgressMonitorSwingWorker;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;
import org.esa.beam.framework.datamodel.Placemark;
import org.esa.beam.framework.datamodel.PlacemarkGroup;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.VectorDataNode;
import org.esa.beam.framework.ui.AppContext;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.LiteShape2;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

class GridPointExportSwingWorker extends ProgressMonitorSwingWorker<List<Exception>, File> {

    private final AppContext appContext;
    private final ExportParameter exportParameter;

    GridPointExportSwingWorker(AppContext appContext, ExportParameter exportParameter) {
        super(appContext.getApplicationWindow(), "Exporting grid points");
        this.appContext = appContext;
        this.exportParameter = exportParameter;
    }

    @Override
    protected List<Exception> doInBackground(ProgressMonitor pm) throws Exception {
        final List<Exception> problemList = new ArrayList<>();

        GridPointFilterStream filterStream = null;
        try {
            final File targetFile = exportParameter.getTargetFile();
            final String exportFormat = exportParameter.getExportFormat();
            if (GridPointExportDialog.NAME_CSV.equals(exportFormat)) {
                filterStream = new CsvExportStream(new PrintWriter(targetFile), ";");
            } else {
                filterStream = new EEExportStream(targetFile);
            }
            final GridPointFilter gridPointFilter = getGridPointFilter();
            final GridPointFilterStreamHandler handler = new GridPointFilterStreamHandler(filterStream,
                    gridPointFilter);

            if (exportParameter.isUseSelectedProduct()) {
                final Product selectedProduct = appContext.getSelectedProduct();
                handler.processProduct(selectedProduct, pm);
            } else {
                final File sourceDirectory = exportParameter.getSourceDirectory();
                final boolean recursive = exportParameter.isRecursive();
                handler.processDirectory(sourceDirectory, recursive, pm, problemList);
            }
        } finally {
            if (filterStream != null) {
                filterStream.close();
            }
        }
        return problemList;
    }

    @Override
    protected void done() {
        try {
            final List<Exception> problemList = get();
            if (!problemList.isEmpty()) {
                final StringBuilder message = new StringBuilder();
                message.append("The following problem(s) have occurred:\n");
                for (final Exception problem : problemList) {
                    problem.printStackTrace();
                    message.append("  ");
                    message.append(problem.getMessage());
                    message.append("\n");
                }
                appContext.handleError(message.toString(), null);
            }
        } catch (InterruptedException e) {
            appContext.handleError(MessageFormat.format(
                    "An error occurred: {0}", e.getMessage()), e);
        } catch (ExecutionException e) {
            appContext.handleError(MessageFormat.format(
                    "An error occurred: {0}", e.getCause().getMessage()), e.getCause());
        }
    }

    private GridPointFilter getGridPointFilter() {
        final int roiType = exportParameter.getRoiType();
        switch (roiType) {
            case 0: {
                final MultiFilter multiFilter = new MultiFilter();
                final VectorDataNode geometry = exportParameter.getGeometry();
                if (geometry != null) {
                    final FeatureIterator<SimpleFeature> featureIterator = geometry.getFeatureCollection().features();

                    while (featureIterator.hasNext()) {
                        final Shape featureShape;
                        final SimpleFeature feature = featureIterator.next();
                        if (feature.getDefaultGeometry() instanceof Point) {
                            featureShape = getPointShape(feature);
                        } else {
                            featureShape = getAreaShape(feature);
                        }
                        if (featureShape != null) {
                            multiFilter.add(new RegionFilter(featureShape));
                        }
                    }
                }
                return multiFilter;
            }
            case 1: {
                final MultiFilter multiFilter = new MultiFilter();
                final Product selectedProduct = appContext.getSelectedProduct();
                if (selectedProduct != null) {
                    final PlacemarkGroup pinGroup = selectedProduct.getPinGroup();
                    for (Placemark pin : pinGroup.toArray(new Placemark[pinGroup.getNodeCount()])) {
                        multiFilter.add(new RegionFilter(getPointShape(pin.getFeature())));
                    }
                }
                return multiFilter;
            }
            case 2: {
                final double north = exportParameter.getNorth();
                final double south = exportParameter.getSouth();
                final double east = exportParameter.getEast();
                final double west = exportParameter.getWest();
                return new RegionFilter(new Rectangle2D.Double(west, south, east - west, north - south));
            }
            default:
                // cannot happen
                throw new IllegalStateException(MessageFormat.format("Illegal ROI type: {0}", roiType));
        }
    }

    private Shape getAreaShape(SimpleFeature feature) {
        try {
            final Object geometry = feature.getDefaultGeometry();
            if (geometry instanceof Geometry) {
                return new LiteShape2((Geometry) geometry, null, null, true);
            }
        } catch (TransformException | FactoryException e) {
            // ignore
        }
        return null;
    }

    private Shape getPointShape(SimpleFeature feature) {
        final Point point = (Point) feature.getDefaultGeometry();
        final double lon = point.getX();
        final double lat = point.getY();

        return new Rectangle2D.Double(lon, lat, 0.0, 0.0);
    }
}
