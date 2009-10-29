package org.esa.beam.dataio.smos;

import org.esa.beam.framework.datamodel.Product;

import java.util.Map;

class DPV extends DP {

    DPV(Product product, Map<String, FieldValueProvider> valueProviderMap, boolean accuracy) {
        super(product, valueProviderMap, accuracy);
    }

    @Override
    protected double compute(double btx, double bty, double aa, double bb) {
        return aa * bty - bb * btx;
    }
}