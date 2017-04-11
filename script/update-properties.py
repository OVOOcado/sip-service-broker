#!/usr/bin/python

import sys
import os
import shutil
import xml.etree.ElementTree as ET
from zipfile import ZipFile


if len(sys.argv) != 4:
   print("\nUsage:\n {} config.properties ovoo-sip-broker-ra-du-<version>.jar config_suffix\n".format(sys.argv[0]))
   sys.exit(1)


config_file = sys.argv[1]
package_jar = sys.argv[2]
config_suffix = sys.argv[3]
new_deploy_config = "deploy-config.tmp"
tmpdir = "tmpdir"

print("Preparing custom broker package for: " + config_suffix)
print("Config file: " + config_file)
print("Broker jar : " + package_jar)

# remove old tmp dir and files
shutil.rmtree(tmpdir, ignore_errors=True)
if os.path.isfile(new_deploy_config) :
   os.unlink(new_deploy_config)

# read config properties for given deployment
config_properties = dict( l.rstrip().split('=') for l in open(config_file)
              if not l.startswith("#") )

# extrack package
zip = ZipFile(package_jar)
zip.extractall("tmpdir")

old_deploy_config = tmpdir + "/META-INF/deploy-config.xml"

# parse deploy-config.xml and update properties 
tree = ET.parse(old_deploy_config)
root = tree.getroot()
props = root.findall("./ra-entity/properties/property")
for prop in props:
   param = prop.attrib["name"]
   if param in config_properties:
      newValue = config_properties[param] 
      prop.attrib["value"] = newValue
      print('Property {} set to {}'.format(param, newValue))

tree.write(new_deploy_config, encoding="UTF-8")

shutil.copyfile(new_deploy_config, old_deploy_config)

# make new archive
filename = os.path.basename(package_jar)
filename_no_ext = os.path.splitext(filename)[0]
new_archive_name = filename_no_ext + "_" + config_suffix

shutil.make_archive(new_archive_name, "zip", root_dir=tmpdir)

# change it to jar
os.rename(new_archive_name+".zip", new_archive_name+".jar")

print("\nCreated new package with custom deploy-config: " + new_archive_name+".jar\n")
