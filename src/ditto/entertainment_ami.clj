(ns ditto.entertainment-ami
  (:require [ditto
             [bake-common :refer :all]
             [aws :as aws]
             [nokia :as nokia]]
            [cheshire.core :as json]
            [environ.core :refer [env]]
            [clj-time
             [core :as time-core]
             [format :as time-format]]
            [clojure.java.io :as io]))

(defn entertainment-base-ami-id
  "Returns the id of the latest entertainment base ami"
  [type]
  ( -> (aws/owned-images-by-name (str "entertainment-base-" (name type) "*"))
       (last)
       :ImageId))

(defn ent-ami-name
  "Returns the ami name for date/time now"
  [type]
  (str "entertainment-base-"
       (name type)
       "-"
       (time-format/unparse (time-format/formatter "YYYY-MM-dd_HH-mm-ss") (time-core/now))))

(def ent-yum-repo
  "Set up the entertainment yum repo"
  (shell (str "echo \""
              (slurp (io/resource "nokia-internal.repo"))
              "\" >> /etc/yum.repos.d/nokia-internal.repo")
         "echo \"iam_role=1\" >> /etc/yum/pluginconf.d/nokia-s3yum.conf"
         "yum-config-manager --enable nokia-epel >> /var/log/baking.log 2>&1"))

(def puppet
  "Set up puppet and run once, blocking

   We also need to do all our cleanup in this step as we don't have root after this has run!
   Due to puppet setting up the various auth concerns."
  (shell "export LD_LIBRARY_PATH=/opt/rh/ruby193/root/usr/lib64"
         "PUPPETD=\"PATH=/opt/rh/ruby193/root/usr/local/bin/:/opt/rh/ruby193/root/usr/bin/:/sbin:/usr/sbin:/bin:/usr/bin /opt/rh/ruby193/root/usr/local/bin/puppet\""
         "yum install -y puppet >> /var/log/baking.log 2>&1"
         "scl enable ruby193 ' /opt/rh/ruby193/root/usr/local/bin/puppet agent --onetime --no-daemonize --server puppetaws.brislabs.com'"
         "rm -rf /var/lib/puppet/ssl"
         "echo \"nokia-rebake 	ALL=(ALL)	NOPASSWD: ALL\" >> /etc/sudoers"
         "rm /tmp/script.sh"))

(def ruby-193
  "Install ruby-193 - required to run puppet faster"
  (shell "yum install -y ruby193"
         "yum install -y ruby193-rubygem-puppet"
         "yum install -y ruby193-rubygem-ruby-shadow"))

(defn motd [parent-ami]
  "Set the message of the day"
  (shell "echo -e \"\\nEntertainment Base AMI\" >> /etc/motd"
         (format "echo -e \"\\nBake date: %s\" >> /etc/motd"
                 (time-format/unparse (time-format/formatters :date-time-no-ms) (time-core/now)))
         (format "echo -e \"\\nSource AMI: %s\\n\" >> /etc/motd" parent-ami)))

(def cloud-final
  "Run cloud final just before last, not last"
  (shell "chkconfig cloud-final off"
         "sudo sed -i \"s/# chkconfig:   - 99 01/# chkconfig:   - 98 01/\" /etc/rc.d/init.d/cloud-final"
         "chkconfig cloud-final on"))

(def packer
  "Download and unzip packer into /opt/packer"
  (shell "wget -O /tmp/packer.zip \"http://966b.http.dal05.cdn.softlayer.net/data-production/23851027b087eb4405f7fc4142c3ddda4d64aa73?filename=0.3.7_linux_amd64.zip\""
         "mkdir /opt/packer"
         "unzip /tmp/packer.zip -d /opt/packer"
         "chmod -R 777 /opt/packer"))

(def user-cleanup
  "Cleanup the nokia baking user and reset the lock file so that a new one is created on next bake"
  (shell "rm /var/lib/nokia-tools/init.lock"))

(defn ebs-template
  "Generate a new ami ebs backed packer builder template"
  [parent-ami]
  {:builders [{:access_key (env :service-aws-access-key)
               :ami_name (ent-ami-name :ebs)
               :iam_instance_profile "baking"
               :instance_type "t1.micro"
               :region "eu-west-1"
               :secret_key (env :service-aws-secret-key)
               :security_group_id "sg-c453b4ab"
               :source_ami parent-ami
               :temporary_key_pair_name "nokia-{{uuid}}"
               :ssh_timeout "5m"
               :ssh_username "nokia"
               :subnet_id "subnet-bdc08fd5"
               :type "amazon-ebs"
               :vpc_id "vpc-7bc88713"}]
   :provisioners [(motd parent-ami)
                  ent-yum-repo
                  ruby-193
                  packer
                  cloud-final
                  user-cleanup
                  puppet]})

;; TODO - get account id from env - remember to remove the hyphens!
(defn instance-template
  "Generate a new ami instance backed packer builder template"
  []
  (let [parent-ami (nokia/latest-nokia-ami :instance)]
    {:builders [{:access_key (env :service-aws-access-key)
                 :account_id "513894612423"
                 :ami_name (ent-ami-name :instance)
                 :iam_instance_profile "baking"
                 :instance_type "t1.micro"
                 :region "eu-west-1"
                 :s3_bucket "ent-instance-amis"
                 :secret_key (env :service-aws-secret-key)
                 :security_group_id "sg-c453b4ab"
                 :source_ami parent-ami
                 :ssh_timeout "5m"
                 :ssh_username "nokia"
                 :subnet_id "subnet-bdc08fd5"
                 :temporary_key_pair_name "nokia-{{uuid}}"
                 :type "amazon-instance"
                 :vpc_id "vpc-7bc88713"
                 :x509_cert_path "/home/bgriffit/.ssh/certificate.pem"
                 :x509_key_path  "/home/bgriffit/.ssh/bgriffit-awspem.pem"}]
     :provisioners [(motd parent-ami)
                    ent-yum-repo
                    ruby-193
                    packer
                    cloud-final
                    puppet]}))

;; TODO - this is loading the latest ami not using the passed in ami
(defn create-base-ami
  "Creates a new entertainment base-ami from the parent ami id"
  [parent-ami & [server-type]]
  (if (= server-type :ebs)
    (json/generate-string (ebs-template parent-ami))
    (json/generate-string (instance-template))))
