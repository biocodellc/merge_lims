-- MySQL dump 10.13  Distrib 8.4.6, for macos15.4 (arm64)
--
-- Host: 127.0.0.1    Database: lims
-- ------------------------------------------------------
-- Server version	8.0.41

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `assembly`
--

DROP TABLE IF EXISTS `assembly`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `assembly` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `extraction_id` varchar(45) NOT NULL,
  `workflow` int unsigned NOT NULL,
  `progress` varchar(45) NOT NULL,
  `consensus` longtext,
  `params` longtext,
  `coverage` float DEFAULT NULL,
  `disagreements` int unsigned DEFAULT NULL,
  `edits` longtext,
  `reference_seq_id` int unsigned DEFAULT NULL,
  `confidence_scores` longtext,
  `trim_params_fwd` longtext,
  `trim_params_rev` longtext,
  `other_processing_fwd` longtext,
  `other_processing_rev` longtext,
  `date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `notes` longtext,
  `technician` varchar(255) DEFAULT NULL,
  `bin` varchar(255) DEFAULT NULL,
  `ambiguities` int DEFAULT NULL,
  `submitted` tinyint NOT NULL DEFAULT '0',
  `editrecord` longtext,
  `failure_reason` int DEFAULT NULL,
  `failure_notes` longtext,
  PRIMARY KEY (`id`),
  KEY `assembly_date_i` (`date`),
  KEY `assembly_progress_i` (`progress`),
  KEY `assembly_submitted_i` (`submitted`),
  KEY `assembly_technician_i` (`technician`),
  KEY `assembly_failure_reason_i` (`failure_reason`),
  KEY `assembly_workflow_i` (`workflow`),
  CONSTRAINT `assembly_failure_reason_fk` FOREIGN KEY (`failure_reason`) REFERENCES `failure_reason` (`id`) ON DELETE SET NULL,
  CONSTRAINT `assembly_workflow_fk` FOREIGN KEY (`workflow`) REFERENCES `workflow` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=205464 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cycle`
--

DROP TABLE IF EXISTS `cycle`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cycle` (
  `id` int NOT NULL AUTO_INCREMENT,
  `thermocycleId` int DEFAULT NULL,
  `repeats` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `cycle_thermocycleid_i` (`thermocycleId`),
  CONSTRAINT `cycle_thermocycleid_fk` FOREIGN KEY (`thermocycleId`) REFERENCES `thermocycle` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=1177 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cyclesequencing`
--

DROP TABLE IF EXISTS `cyclesequencing`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cyclesequencing` (
  `id` int NOT NULL AUTO_INCREMENT,
  `primerName` varchar(64) NOT NULL,
  `primerSequence` varchar(999) NOT NULL,
  `notes` longtext NOT NULL,
  `date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `workflow` int unsigned DEFAULT NULL,
  `thermocycle` int NOT NULL,
  `plate` int unsigned NOT NULL,
  `location` int NOT NULL,
  `extractionId` varchar(45) NOT NULL,
  `cocktail` int unsigned DEFAULT NULL,
  `progress` varchar(45) NOT NULL,
  `cleanupPerformed` tinyint NOT NULL DEFAULT '0',
  `cleanupMethod` varchar(99) NOT NULL,
  `direction` varchar(32) NOT NULL,
  `technician` varchar(90) NOT NULL DEFAULT '',
  `gelimage` longblob,
  PRIMARY KEY (`id`),
  KEY `cyclesequencing_thermocycle_i` (`thermocycle`),
  KEY `cyclesequencing_cocktail_i` (`cocktail`),
  KEY `cyclesequencing_plate_i` (`plate`),
  KEY `cyclesequencing_workflow_i` (`workflow`),
  CONSTRAINT `cyclesequencing_cocktail_fk` FOREIGN KEY (`cocktail`) REFERENCES `cyclesequencing_cocktail` (`id`),
  CONSTRAINT `cyclesequencing_plate_fk` FOREIGN KEY (`plate`) REFERENCES `plate` (`id`),
  CONSTRAINT `cyclesequencing_workflow_fk` FOREIGN KEY (`workflow`) REFERENCES `workflow` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=576059 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cyclesequencing_cocktail`
--

DROP TABLE IF EXISTS `cyclesequencing_cocktail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cyclesequencing_cocktail` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(99) NOT NULL,
  `ddh2o` double NOT NULL,
  `buffer` double NOT NULL,
  `bigDye` double NOT NULL,
  `notes` longtext NOT NULL,
  `bufferConc` double NOT NULL,
  `bigDyeConc` double NOT NULL,
  `templateConc` double NOT NULL,
  `primerConc` double NOT NULL,
  `primerAmount` double NOT NULL,
  `extraItem` mediumtext NOT NULL,
  `extraItemAmount` double NOT NULL,
  `templateAmount` double NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=34 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cyclesequencing_thermocycle`
--

DROP TABLE IF EXISTS `cyclesequencing_thermocycle`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cyclesequencing_thermocycle` (
  `id` int NOT NULL AUTO_INCREMENT,
  `cycle` int NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=20 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `databaseversion`
--

DROP TABLE IF EXISTS `databaseversion`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `databaseversion` (
  `version` int unsigned NOT NULL AUTO_INCREMENT,
  PRIMARY KEY (`version`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `extraction`
--

DROP TABLE IF EXISTS `extraction`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `extraction` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `method` varchar(45) NOT NULL,
  `volume` double NOT NULL,
  `dilution` double DEFAULT NULL,
  `parent` varchar(45) NOT NULL,
  `sampleId` varchar(45) NOT NULL,
  `extractionId` varchar(45) NOT NULL,
  `plate` int unsigned NOT NULL,
  `location` int unsigned NOT NULL,
  `notes` longtext NOT NULL,
  `extractionBarcode` varchar(45) NOT NULL,
  `previousPlate` varchar(45) NOT NULL,
  `previousWell` varchar(45) NOT NULL,
  `technician` varchar(90) NOT NULL DEFAULT '',
  `concentrationStored` tinyint NOT NULL DEFAULT '0',
  `concentration` double NOT NULL DEFAULT '0',
  `gelimage` longblob,
  `control` varchar(45) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  UNIQUE KEY `extraction_extractionid_u` (`extractionId`),
  KEY `extraction_date_i` (`date`),
  KEY `extraction_extractionbarcode_i` (`extractionBarcode`),
  KEY `extraction_plate_i` (`plate`),
  KEY `extraction_sampleid_i` (`sampleId`)
) ENGINE=InnoDB AUTO_INCREMENT=342848 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `failure_reason`
--

DROP TABLE IF EXISTS `failure_reason`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `failure_reason` (
  `id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(80) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `gel_quantification`
--

DROP TABLE IF EXISTS `gel_quantification`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `gel_quantification` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `extractionId` int unsigned NOT NULL,
  `plate` int unsigned NOT NULL,
  `location` int unsigned NOT NULL,
  `technician` varchar(255) DEFAULT NULL,
  `notes` longtext,
  `volume` double DEFAULT NULL,
  `gelImage` longblob,
  `gelBuffer` varchar(255) DEFAULT NULL,
  `gelConc` double DEFAULT NULL,
  `stain` varchar(255) DEFAULT NULL,
  `stainConc` varchar(255) DEFAULT NULL,
  `stainMethod` varchar(255) DEFAULT NULL,
  `gelLadder` varchar(255) DEFAULT NULL,
  `threshold` int DEFAULT NULL,
  `aboveThreshold` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `gel_quantification_extractionid_i` (`extractionId`),
  KEY `gel_quantification_plate_i` (`plate`),
  CONSTRAINT `gel_quantification_extractionid_fk` FOREIGN KEY (`extractionId`) REFERENCES `extraction` (`id`) ON DELETE CASCADE,
  CONSTRAINT `gel_quantification_plate_fk` FOREIGN KEY (`plate`) REFERENCES `plate` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=430 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `gelimages`
--

DROP TABLE IF EXISTS `gelimages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `gelimages` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `plate` int NOT NULL DEFAULT '0',
  `imageData` longblob,
  `notes` longtext NOT NULL,
  `name` varchar(45) NOT NULL DEFAULT 'Image',
  PRIMARY KEY (`id`),
  KEY `gelimages_plate_i` (`plate`)
) ENGINE=InnoDB AUTO_INCREMENT=4990 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pcr`
--

DROP TABLE IF EXISTS `pcr`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pcr` (
  `id` int NOT NULL AUTO_INCREMENT,
  `prName` varchar(64) DEFAULT NULL,
  `prSequence` varchar(999) DEFAULT NULL,
  `date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `workflow` int unsigned DEFAULT NULL,
  `plate` int unsigned NOT NULL,
  `location` int NOT NULL,
  `cocktail` int unsigned NOT NULL,
  `progress` varchar(45) NOT NULL,
  `extractionId` varchar(45) NOT NULL,
  `thermocycle` int NOT NULL DEFAULT '-1',
  `cleanupPerformed` tinyint NOT NULL DEFAULT '0',
  `cleanupMethod` varchar(45) NOT NULL,
  `notes` longtext NOT NULL,
  `revPrName` varchar(64) NOT NULL,
  `revPrSequence` varchar(999) NOT NULL,
  `technician` varchar(90) NOT NULL DEFAULT '',
  `gelimage` longblob,
  PRIMARY KEY (`id`),
  KEY `pcr_cocktail_i` (`cocktail`),
  KEY `pcr_plate_i` (`plate`),
  KEY `pcr_workflow_i` (`workflow`),
  CONSTRAINT `pcr_cocktail_fk` FOREIGN KEY (`cocktail`) REFERENCES `pcr_cocktail` (`id`),
  CONSTRAINT `pcr_plate_fk` FOREIGN KEY (`plate`) REFERENCES `plate` (`id`),
  CONSTRAINT `pcr_workflow_fk` FOREIGN KEY (`workflow`) REFERENCES `workflow` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=371607 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pcr_cocktail`
--

DROP TABLE IF EXISTS `pcr_cocktail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pcr_cocktail` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(99) NOT NULL,
  `ddH20` double NOT NULL,
  `buffer` double NOT NULL,
  `mg` double NOT NULL,
  `bsa` double NOT NULL,
  `dNTP` double NOT NULL,
  `taq` double NOT NULL,
  `notes` longtext NOT NULL,
  `bufferConc` double NOT NULL,
  `mgConc` double NOT NULL,
  `dNTPConc` double NOT NULL,
  `taqConc` double NOT NULL,
  `templateConc` double NOT NULL,
  `bsaConc` double NOT NULL,
  `fwPrAmount` double NOT NULL,
  `fwPrConc` double NOT NULL,
  `revPrAmount` double NOT NULL,
  `revPrConc` double NOT NULL,
  `extraItem` mediumtext NOT NULL,
  `extraItemAmount` double NOT NULL,
  `templateAmount` double NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=120 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pcr_thermocycle`
--

DROP TABLE IF EXISTS `pcr_thermocycle`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pcr_thermocycle` (
  `id` int NOT NULL AUTO_INCREMENT,
  `cycle` int DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=271 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `plate`
--

DROP TABLE IF EXISTS `plate`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `plate` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL DEFAULT 'plate',
  `date` date DEFAULT NULL,
  `size` int NOT NULL,
  `type` varchar(45) NOT NULL,
  `thermocycle` int NOT NULL DEFAULT '-1',
  PRIMARY KEY (`id`,`name`),
  KEY `plate_date_i` (`date`),
  KEY `plate_name_i` (`name`),
  KEY `plate_type_i` (`type`)
) ENGINE=InnoDB AUTO_INCREMENT=15347 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `properties`
--

DROP TABLE IF EXISTS `properties`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `properties` (
  `name` varchar(255) NOT NULL,
  `value` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sequencing_result`
--

DROP TABLE IF EXISTS `sequencing_result`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sequencing_result` (
  `reaction` int NOT NULL DEFAULT '0',
  `assembly` int unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`reaction`,`assembly`),
  KEY `sequencing_result_assembly_i` (`assembly`),
  KEY `sequencing_result_reaction_i` (`reaction`),
  CONSTRAINT `sequencing_result_assembly_fk` FOREIGN KEY (`assembly`) REFERENCES `assembly` (`id`) ON DELETE CASCADE,
  CONSTRAINT `sequencing_result_reaction_fk` FOREIGN KEY (`reaction`) REFERENCES `cyclesequencing` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `state`
--

DROP TABLE IF EXISTS `state`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `state` (
  `id` int NOT NULL AUTO_INCREMENT,
  `temp` int unsigned NOT NULL,
  `length` int unsigned NOT NULL,
  `cycleId` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `state_cycleid_i` (`cycleId`),
  CONSTRAINT `state_cycleid_fk` FOREIGN KEY (`cycleId`) REFERENCES `cycle` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=2102 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `thermocycle`
--

DROP TABLE IF EXISTS `thermocycle`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `thermocycle` (
  `id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(64) DEFAULT NULL,
  `notes` longtext NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=302 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `traces`
--

DROP TABLE IF EXISTS `traces`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `traces` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `reaction` int NOT NULL,
  `name` varchar(96) NOT NULL,
  `data` longblob NOT NULL,
  PRIMARY KEY (`id`),
  KEY `traces_reaction_i` (`reaction`),
  CONSTRAINT `traces_reaction_fk` FOREIGN KEY (`reaction`) REFERENCES `cyclesequencing` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=513712 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `workflow`
--

DROP TABLE IF EXISTS `workflow`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `workflow` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(45) NOT NULL DEFAULT 'workflow',
  `extractionId` int unsigned NOT NULL,
  `date` timestamp NOT NULL DEFAULT '2010-01-01 22:00:00',
  `locus` varchar(45) NOT NULL DEFAULT 'COI',
  PRIMARY KEY (`id`),
  UNIQUE KEY `workflow_name_u` (`name`),
  KEY `workflow_date_i` (`date`),
  KEY `workflow_locus_i` (`locus`),
  KEY `workflow_extractionid_i` (`extractionId`),
  CONSTRAINT `workflow_extractionid_fk` FOREIGN KEY (`extractionId`) REFERENCES `extraction` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=357475 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2025-08-12 11:50:17
