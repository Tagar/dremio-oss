/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.dac.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.dremio.common.util.TestTools;
import com.dremio.dac.server.BaseTestServer;
import com.dremio.dac.service.catalog.CatalogServiceHelper;
import com.dremio.exec.store.dfs.NASConf;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.file.FileFormat;
import com.dremio.service.namespace.file.proto.JsonFileConfig;
import com.dremio.service.namespace.file.proto.TextFileConfig;
import com.dremio.service.namespace.space.proto.SpaceConfig;

/**
 * Tests for CatalogResource
 */
public class TestCatalogResource extends BaseTestServer {
  private static final String CATALOG_PATH = "/catalog/";

  @BeforeClass
  public static void init() throws Exception {
    BaseTestServer.init();

    // setup space
    NamespaceKey key = new NamespaceKey("mySpace");
    SpaceConfig spaceConfig = new SpaceConfig();
    spaceConfig.setName("mySpace");
    newNamespaceService().addOrUpdateSpace(key, spaceConfig);
  }

  @AfterClass
  public static void shutdown() throws Exception {
    // setup space
    NamespaceKey key = new NamespaceKey("mySpace");
    SpaceConfig space = newNamespaceService().getSpace(key);
    newNamespaceService().deleteSpace(key, space.getVersion());
  }

  @Test
  public void testListTopLevelCatalog() throws Exception {
    // home space always exists
    int topLevelCount = newSourceService().getSources().size() + newNamespaceService().getSpaces().size() + 1;

    ResponseList<CatalogItem> items = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildGet(), new GenericType<ResponseList<CatalogItem>>() {});
    assertEquals(items.getData().size(), topLevelCount);

    int homeCount = 0;
    int spaceCount = 0;
    int sourceCount = 0;

    for (CatalogItem item : items.getData()) {
      if (item.getType() == CatalogItem.CatalogItemType.CONTAINER) {
         if (item.getContainerType() == CatalogItem.ContainerSubType.HOME) {
           homeCount++;
         }

        if (item.getContainerType() == CatalogItem.ContainerSubType.SPACE) {
           spaceCount++;
        }


        if (item.getContainerType() == CatalogItem.ContainerSubType.SOURCE) {
          sourceCount++;
        }
      }
    }

    assertEquals(homeCount, 1);
    assertEquals(spaceCount, newNamespaceService().getSpaces().size());
    assertEquals(sourceCount, 1);
  }

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testSpace() throws Exception {
    // create a new space
    Space newSpace = new Space(null, "final frontier", null, null, null);

    Space space = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newSpace)), new GenericType<Space>() {});
    SpaceConfig spaceConfig = newNamespaceService().getSpace(new NamespaceKey(newSpace.getName()));

    assertEquals(space.getId(), spaceConfig.getId().getId());
    assertEquals(space.getName(), spaceConfig.getName());

    // make sure that trying to create the space again fails
    expectStatus(Response.Status.CONFLICT, getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newSpace)));

    // delete the space
    expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(spaceConfig.getId().getId())).buildDelete());
    thrown.expect(NamespaceException.class);
    newNamespaceService().getSpace(new NamespaceKey(spaceConfig.getName()));
  }

  @Test
  public void testFoldersInSpace() throws Exception {
    // create a new space
    Space newSpace = new Space(null, "final frontier", null, null, null);
    Space space = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newSpace)), new GenericType<Space>() {});

    // no children at this point
    assertNull(space.getChildren());

    // add a folder
    Folder newFolder = new Folder(null, Arrays.asList(space.getName(), "myFolder"), null, null);
    Folder folder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newFolder)), new GenericType<Folder>() {});
    assertEquals(newFolder.getPath(), folder.getPath());

    // make sure folder shows up under space
    space = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(space.getId())).buildGet(), new GenericType<Space>() {});

    // make sure that trying to create the folder again fails
    expectStatus(Response.Status.CONFLICT, getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newFolder)));

    // one child at this point
    assertEquals(space.getChildren().size(), 1);
    assertEquals(space.getChildren().get(0).getId(), folder.getId());

    // delete the folder
    expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(folder.getId())).buildDelete());
    space = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(space.getId())).buildGet(), new GenericType<Space>() {});
    assertEquals(space.getChildren().size(), 0);

    newNamespaceService().deleteSpace(new NamespaceKey(space.getName()), Long.valueOf(space.getTag()));
  }

  @Test
  public void testVDSInSpace() throws Exception {
    // create a new space
    Space newSpace = new Space(null, "final frontier", null, null, null);
    Space space = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newSpace)), new GenericType<Space>() {});

    // add a folder
    Folder newFolder = new Folder(null, Arrays.asList(space.getName(), "myFolder"), null, null);
    Folder folder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newFolder)), new GenericType<Folder>() {});

    // create a VDS in the space
    Dataset newVDS = createVDS(Arrays.asList(space.getName(), "myFolder", "myVDS"),"select * from sys.version");
    Dataset vds = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newVDS)), new GenericType<Dataset>() {});

    // make sure that trying to create the vds again fails
    expectStatus(Response.Status.CONFLICT, getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newVDS)));

    // folder should now have children
    folder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(folder.getId())).buildGet(), new GenericType<Folder>() {});
    assertEquals(folder.getChildren().size(), 1);
    assertEquals(folder.getChildren().get(0).getId(), vds.getId());

    // test rename of a vds
    Dataset renamedVDS = new Dataset(
      vds.getId(),
      vds.getType(),
      Arrays.asList(space.getName(), "myFolder", "myVDSRenamed"),
      null,
      null,
      vds.getTag(),
      null,
      vds.getSql(),
      null,
      null
    );
    vds = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(renamedVDS.getId())).buildPut(Entity.json(renamedVDS)), new GenericType<Dataset>() {});

    folder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(folder.getId())).buildGet(), new GenericType<Folder>() {});
    assertEquals(folder.getChildren().size(), 1);
    assertEquals(folder.getChildren().get(0).getId(), vds.getId());
    assertEquals(folder.getChildren().get(0).getPath().get(2), "myVDSRenamed");

    // delete the vds
    expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(vds.getId())).buildDelete());
    folder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(folder.getId())).buildGet(), new GenericType<Folder>() {});
    assertEquals(folder.getChildren().size(), 0);

    newNamespaceService().deleteSpace(new NamespaceKey(space.getName()), Long.valueOf(space.getTag()));
  }

  @Test
  public void testSource() throws Exception {
    Source source = createSource();

    // make sure we can fetch the source by id
    source = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(source.getId())).buildGet(), new GenericType<Source>() {});

    // make sure that trying to create the source again fails
    NASConf nasConf = new NASConf();
    nasConf.path = TestTools.getWorkingPath() + "/src/test/resources";

    Source newSource = new Source();
    newSource.setName("catalog-test");
    newSource.setType("NAS");
    newSource.setConfig(nasConf);
    expectStatus(Response.Status.CONFLICT, getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newSource)));

    // edit source
    source.setAccelerationRefreshPeriodMs(0);
    source = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(source.getId())).buildPut(Entity.json(source)), new GenericType<Source>() {});

    assertEquals(source.getTag(), "1");
    assertEquals((long) source.getAccelerationRefreshPeriodMs(), 0);

    // adding a folder to a source should fail
    Folder newFolder = new Folder(null, Arrays.asList(source.getName(), "myFolder"), null, null);
    expectStatus(Response.Status.BAD_REQUEST, getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newFolder)));

    // delete source
    expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(source.getId())).buildDelete());

    thrown.expect(NamespaceException.class);
    newNamespaceService().getSource(new NamespaceKey(source.getName()));
  }

  @Test
  public void testSourceBrowsing() throws Exception {
    Source source = createSource();

    // browse to the json directory
    String id = getFolderIdByName(source.getChildren(), "\"json\"");
    assertNotNull(id, "Failed to find json directory");

    // deleting a folder on a source should fail
    expectStatus(Response.Status.BAD_REQUEST, getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(com.dremio.common.utils.PathUtils.encodeURIComponent(id))).buildDelete());

    newNamespaceService().deleteSource(new NamespaceKey(source.getName()), Long.valueOf(source.getTag()));
  }

  @Test
  public void testSourcePromoting() throws Exception {
    Source source = createSource();

    // browse to the json directory
    String id = getFolderIdByName(source.getChildren(), "\"json\"");
    assertNotNull(id, "Failed to find json directory");

    // load the json dir
    Folder folder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(com.dremio.common.utils.PathUtils.encodeURIComponent(id))).buildGet(), new GenericType<Folder>() {});

    String fileId = null;

    for (CatalogItem item : folder.getChildren()) {
      List<String> path = item.getPath();
      // get the numbers.json file
      if (item.getType() == CatalogItem.CatalogItemType.FILE && path.get(path.size() - 1).equals("\"numbers.json\"")) {
        fileId = item.getId();
        break;
      }
    }

    assertNotNull(fileId, "Failed to find numbers.json file");

    // load the file
    File file = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(com.dremio.common.utils.PathUtils.encodeURIComponent(fileId))).buildGet(), new GenericType<File>() {});

    // promote the file (dac/backend/src/test/resources/json/numbers.json)
    Dataset dataset = createPDS(CatalogServiceHelper.getPathFromInternalId(file.getId()), new JsonFileConfig());

    dataset = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(com.dremio.common.utils.PathUtils.encodeURIComponent(fileId))).buildPost(Entity.json(dataset)), new GenericType<Dataset>() {});

    // load the dataset
    dataset = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(dataset.getId())).buildGet(), new GenericType<Dataset>() {});

    // unpromote file
    expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(dataset.getId())).buildDelete());

    // dataset should no longer exist
    expectStatus(Response.Status.NOT_FOUND, getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(dataset.getId())).buildGet());

    // promote a folder that contains several csv files (dac/backend/src/test/resources/datasets/folderdataset)
    String folderId = getFolderIdByName(source.getChildren(), "\"datasets\"");
    assertNotNull(folderId, "Failed to find datasets directory");

    Folder dsFolder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(com.dremio.common.utils.PathUtils.encodeURIComponent(folderId))).buildGet(), new GenericType<Folder>() {});

    String folderDatasetId = getFolderIdByName(dsFolder.getChildren(), "\"folderdataset\"");
    assertNotNull(folderDatasetId, "Failed to find folderdataset directory");

    TextFileConfig textFileConfig = new TextFileConfig();
    textFileConfig.setLineDelimiter("\n");
    dataset = createPDS(CatalogServiceHelper.getPathFromInternalId(folderDatasetId), textFileConfig);

    dataset = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(com.dremio.common.utils.PathUtils.encodeURIComponent(folderDatasetId))).buildPost(Entity.json(dataset)), new GenericType<Dataset>() {});

    // load the promoted dataset
    dataset = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(dataset.getId())).buildGet(), new GenericType<Dataset>() {});

    // unpromote the folder
    expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(dataset.getId())).buildDelete());

    // dataset should no longer exist
    expectStatus(Response.Status.NOT_FOUND, getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(dataset.getId())).buildGet());

    newNamespaceService().deleteSource(new NamespaceKey(source.getName()), Long.valueOf(source.getTag()));
  }

  private Source createSource() {
    NASConf nasConf = new NASConf();
    nasConf.path = TestTools.getWorkingPath() + "/src/test/resources";

    Source newSource = new Source();
    newSource.setName("catalog-test");
    newSource.setType("NAS");
    newSource.setConfig(nasConf);

    // create the source
    return expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newSource)),  new GenericType<Source>() {});
  }

  @Test
  public void testHome() throws Exception {
    ResponseList<CatalogItem> items = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildGet(), new GenericType<ResponseList<CatalogItem>>() {});

    String homeId = null;

    for (CatalogItem item : items.getData()) {
      if (item.getType() == CatalogItem.CatalogItemType.CONTAINER && item.getContainerType() == CatalogItem.ContainerSubType.HOME) {
        homeId = item.getId();
        break;
      }
    }

    // load home space
    Home home = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(homeId)).buildGet(), new GenericType<Home>() {});

    int size = home.getChildren().size();

    // add a folder
    Folder newFolder = new Folder(null, Arrays.asList(home.getName(), "myFolder"), null, null);
    Folder folder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(newFolder)), new GenericType<Folder>() {});
    assertEquals(newFolder.getPath(), folder.getPath());

    // make sure folder shows up under space
    home = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(homeId)).buildGet(), new GenericType<Home>() {});
    assertEquals(home.getChildren().size(), size + 1);

    // make sure that trying to create the folder again fails
    expectStatus(Response.Status.CONFLICT, getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(folder)));

    // load folder
    folder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(folder.getId())).buildGet(), new GenericType<Folder>() {});

    // store a VDS in the folder
    Dataset vds = createVDS(Arrays.asList(home.getName(), "myFolder", "myVDS"), "select * from sys.version");

    Dataset newVDS = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH)).buildPost(Entity.json(vds)), new GenericType<Dataset>() {});

    // folder should have children now
    folder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(folder.getId())).buildGet(), new GenericType<Folder>() {});
    assertEquals(folder.getChildren().size(), 1);

    // delete vds
    expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(newVDS.getId())).buildDelete());

    // folder should have no children now
    folder = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(folder.getId())).buildGet(), new GenericType<Folder>() {});
    assertEquals(folder.getChildren().size(), 0);

    // delete folder
    expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(folder.getId())).buildDelete());

    home = expectSuccess(getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(homeId)).buildGet(), new GenericType<Home>() {});
    assertEquals(home.getChildren().size(), size);
  }

  @Test
  public void testErrors() throws Exception {
    // test non-existent id
    expectStatus(Response.Status.NOT_FOUND, getBuilder(getPublicAPI(3).path(CATALOG_PATH).path("bad-id")).buildGet());

    // test non-existent internal id
    expectStatus(Response.Status.NOT_FOUND, getBuilder(getPublicAPI(3).path(CATALOG_PATH).path(CatalogServiceHelper.generateInternalId(Arrays.asList("bad-id")))).buildGet());
  }

  private String getFolderIdByName(List<CatalogItem> items, String nameToFind) {
    for (CatalogItem item : items) {
      List<String> path = item.getPath();
      if (item.getContainerType() == CatalogItem.ContainerSubType.FOLDER && path.get(path.size() - 1).equals(nameToFind)) {
        return item.getId();
      }
    }

    return null;
  }

  private Dataset createPDS(List<String> path, FileFormat format) {
    return new Dataset(
      null,
      Dataset.DatasetType.PHYSICAL_DATASET,
      path,
      null,
      null,
      null,
      null,
      null,
      null,
      format
    );
  }

  private Dataset createVDS(List<String> path, String sql) {
    return new Dataset(
      null,
      Dataset.DatasetType.VIRTUAL_DATASET,
      path,
      null,
      null,
      null,
      null,
      sql,
      null,
      null
    );
  }
}
