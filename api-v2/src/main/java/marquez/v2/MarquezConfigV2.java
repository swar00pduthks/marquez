/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.v2;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public class MarquezConfigV2 extends Configuration {

    @Valid
    @NotNull
    private DataSourceFactory database = new DataSourceFactory();

    @JsonProperty("db")
    public DataSourceFactory getDataSourceFactory() {
        return database;
    }

    @JsonProperty("db")
    public void setDataSourceFactory(DataSourceFactory database) {
        this.database = database;
    }
}
