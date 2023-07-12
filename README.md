# Morpheus Proxmox Plugin

This plugin provides an integration between Proxmox and Morpheus. A `CloudProvider` (for syncing the Cloud related objects) and `ProvisionProvider` (for provisioning into Proxmox) are implemented  

## Requirements

The following requirements must be met prior to using the Proxmox plugin:

* An API key and Application key that will be used by the plugin to perform API calls to the DataDog API (https://docs.datadoghq.com/account_management/api-app-keys/).
* A compatible Morpheus platform version. The Morpheus platform version must be equal to or higher than the version specified on the plugin.

## Installation

Once the plugin file is downloaded, browse to the Administration -> Integrations -> Plugins section in the Morpheus UI. Click the Upload File button to select your plugin and upload it. The plugin should now be loaded into the environment for use.

## Configuration

With the plugin installed, there are a few configuration steps required before you are able to use the plugin. 

### Plugin Settings

The following settings are available for the DataDog plugin:

|Setting|Description|Required|Default|
|---|---|---|---|
| API Endpoint | The API endpoint used for fetching instance data | No | Public (datadoghq.com) |
| API Key |The generated DataDog API key used to authenticate to the DataDog REST API |  Yes |n/a |
| Application Key | The generated DataDog Application key used to authenticate to the DataDog REST API | Yes |n/a |
| Environments | This toggles the visibility of the tab based upon the Morpheus environment the instance is in. Multiple environments are supported by adding multiple comma separated environments in the text box.| No| any|
| Groups | This toggles the visibility of the tab based upon the Morpheus group the instance is in. Multiple groups are supported by adding multiple comma separated groups in the text box.| No|any |
| Tags | This toggles the visibility of the tab based upon the tag(s) assigned to the instance. The tag key is what is used for the evaluation. Multiple tags are supported by adding multiple comma separated tags in the text box.|No| datadog |
| Labels | This toggles the visibility of the tab based upon the label(s) assigned to the instance. Multiple labels are supported by adding multiple comma separated labels in the text box.|No| any |

#### Features
Cloud sync: Clusters, categories, datastores, networks, virtual private clouds, images, hosts, and virtual machines are fetched from Nutanix and inventoried in Morpheus. Any additions, updates, and removals to these objects are reflected in Morpheus.

Provisioning: Virtual machines can be provisioned from Morpheus via this plugin.
