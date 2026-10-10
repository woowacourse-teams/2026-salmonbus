package com.gustler.localdata;

import java.util.Map;

record DataTarget(String url, String username, boolean shared) {
    static DataTarget resolve(String[] args, Map<String, String> environment) {
        final boolean shared = args.length == 3 && "--shared-dev".equals(args[2]);
        final boolean localOption = args.length == 3
            && ("--recover-collector-only".equals(args[2]) || "--reset-generated".equals(args[2]));
        LocalData.require(args.length == 2 || shared || localOption, "LOCAL_PATHS");
        String routes = shared ? "/srv/salmonbus-dev/data/routes.json" : "/local/data/routes.json";
        String model = shared ? "/srv/salmonbus-dev/models/reference-20261002" : ReferenceBundle.DIRECTORY;
        LocalData.require(routes.equals(args[0]) && model.equals(args[1]), "LOCAL_PATHS");
        String url = shared ? "jdbc:postgresql://127.0.0.1:15432/salmonbus_dev"
            : "jdbc:postgresql://postgres:5432/salmonbus_local";
        String username = shared ? "salmonbus_dev" : "salmonbus_local";
        LocalData.require(url.equals(environment.get("DB_URL")) && username.equals(environment.get("DB_USERNAME")),
            "LOCAL_DATABASE_ONLY");
        String password = environment.get("DB_PASSWORD");
        LocalData.require(password != null && password.matches("[0-9a-f]{48}"), "LOCAL_CREDENTIALS");
        String key = shared ? "shared-dev-placeholder" : "local-only-placeholder";
        LocalData.require(key.equals(environment.get("GBIS_SERVICE_KEY")), "LOCAL_KEY_ONLY");
        for (String name : new String[]{"GBIS_SERVICE_KEY_B", "GBIS_SERVICE_KEY_C", "GBIS_SERVICE_KEY_D"}) {
            LocalData.require(environment.getOrDefault(name, "").isEmpty(), "LOCAL_KEY_ONLY");
        }
        return new DataTarget(url, username, shared);
    }
}
