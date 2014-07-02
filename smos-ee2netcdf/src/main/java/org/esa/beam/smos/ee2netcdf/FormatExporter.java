package org.esa.beam.smos.ee2netcdf;


import org.esa.beam.dataio.netcdf.nc.NFileWriteable;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.Product;

import java.io.IOException;

interface FormatExporter {
    void initialize(Product product);

    void addGlobalAttributes(NFileWriteable nFileWriteable, MetadataElement metadataRoot) throws IOException;

    void addDimensions(NFileWriteable nFileWriteable) throws IOException;

    void addVariables(NFileWriteable nFileWriteable) throws IOException;

    void writeData(NFileWriteable nFileWriteable) throws IOException;
}
